// Locavériss — paiement du Pass Réservation par Mobile Money (CinetPay : Wave, Orange,
// MTN, Moov). Déployer avec :  supabase functions deploy pass-cinetpay --no-verify-jwt
// Secrets à définir :  supabase secrets set CINETPAY_APIKEY=... CINETPAY_SITE_ID=...
//
// Actions (paramètre ?action=) :
//   initier  (appli, connecté)  crée le paiement et renvoie l'adresse de la page CinetPay
//   verifier (appli, connecté)  vérifie une transaction auprès de CinetPay et active le Pass
//   notify   (CinetPay)         notification de paiement ; on revérifie toujours chez CinetPay
//   retour   (navigateur)       page affichée après le paiement
import { createClient } from "jsr:@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const APIKEY = Deno.env.get("CINETPAY_APIKEY") ?? "";
const SITE_ID = Deno.env.get("CINETPAY_SITE_ID") ?? "";
const CINETPAY = "https://api-checkout.cinetpay.com/v2";
const FONCTION = `${SUPABASE_URL}/functions/v1/pass-cinetpay`;

const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

function texte(message: string): Response {
  return new Response(message, { headers: { ...CORS, "Content-Type": "text/plain; charset=utf-8" } });
}

async function utilisateur(req: Request): Promise<string | null> {
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return null;
  const { data, error } = await db.auth.getUser(token);
  return error || !data.user ? null : data.user.id;
}

/** Interroge CinetPay et active le Pass si le paiement est accepté. */
async function verifierTransaction(tx: string) {
  const { data: p } = await db.from("pass").select("*").eq("transaction_id", tx).maybeSingle();
  if (!p || p.statut !== "en_attente") return p;

  const r = await fetch(`${CINETPAY}/payment/check`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ apikey: APIKEY, site_id: SITE_ID, transaction_id: tx }),
  });
  const res = await r.json().catch(() => ({}));
  const statut = res?.data?.status;

  if (statut === "ACCEPTED" && Number(res?.data?.amount ?? 0) >= p.montant) {
    // Un Pass encore actif est prolongé au lieu d'être écrasé.
    const { data: actif } = await db.from("pass").select("fin")
      .eq("utilisateur", p.utilisateur).eq("statut", "paye")
      .gt("fin", new Date().toISOString())
      .order("fin", { ascending: false }).limit(1).maybeSingle();
    const debut = actif?.fin ? new Date(actif.fin) : new Date();
    const fin = new Date(debut.getTime() + p.duree_jours * 86_400_000);
    await db.from("pass")
      .update({ statut: "paye", debut: new Date().toISOString(), fin: fin.toISOString() })
      .eq("id", p.id).eq("statut", "en_attente");
  } else if (statut === "REFUSED") {
    await db.from("pass").update({ statut: "echec" }).eq("id", p.id).eq("statut", "en_attente");
  }
  const { data: maj } = await db.from("pass").select("*").eq("id", p.id).single();
  return maj;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const url = new URL(req.url);
  const action = url.searchParams.get("action") ?? "";

  try {
    if (action === "retour") {
      return texte("Paiement terminé. Retourne dans l'appli Locavériss : ton Pass s'active en quelques secondes.");
    }

    if (action === "notify") {
      // CinetPay envoie cpm_trans_id (formulaire). On ne fait jamais confiance au contenu :
      // le statut est toujours relu chez CinetPay.
      let tx = url.searchParams.get("cpm_trans_id") ?? "";
      if (req.method === "POST") {
        const type = req.headers.get("content-type") ?? "";
        if (type.includes("application/json")) {
          const b = await req.json().catch(() => ({}));
          tx = b.cpm_trans_id ?? tx;
        } else {
          const f = await req.formData().catch(() => null);
          tx = (f?.get("cpm_trans_id") as string | null) ?? tx;
        }
      }
      if (tx) await verifierTransaction(tx);
      return texte("OK");
    }

    if (!APIKEY || !SITE_ID) {
      return json({ message: "Le paiement Mobile Money n'est pas encore activé. Réessaie plus tard." }, 503);
    }
    const uid = await utilisateur(req);
    if (!uid) return json({ message: "Connecte-toi pour acheter le Pass." }, 401);

    if (action === "initier") {
      const { data: r } = await db.from("reglages").select("*").limit(1).single();
      const montant = r?.prix_pass ?? 1500;
      const duree = r?.duree_pass_jours ?? 30;
      const tx = "LV" + Date.now() + Math.floor(Math.random() * 1e6).toString().padStart(6, "0");

      const { error } = await db.from("pass")
        .insert({ utilisateur: uid, transaction_id: tx, montant, duree_jours: duree });
      if (error) return json({ message: "Impossible de créer le paiement." }, 500);

      const r2 = await fetch(`${CINETPAY}/payment`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          apikey: APIKEY,
          site_id: SITE_ID,
          transaction_id: tx,
          amount: montant,
          currency: "XOF",
          description: `Pass Locaveriss ${duree} jours`,
          notify_url: `${FONCTION}?action=notify`,
          return_url: `${FONCTION}?action=retour`,
          channels: "MOBILE_MONEY",
          lang: "fr",
          metadata: uid,
        }),
      });
      const res = await r2.json().catch(() => ({}));
      const lien = res?.data?.payment_url;
      if (!lien) {
        await db.from("pass").update({ statut: "echec" }).eq("transaction_id", tx);
        return json({ message: "CinetPay a refusé la demande : " + (res?.description ?? res?.message ?? "erreur") }, 502);
      }
      return json({ payment_url: lien, transaction_id: tx, montant, duree });
    }

    if (action === "verifier") {
      const b = await req.json().catch(() => ({}));
      const tx = String(b.transaction_id ?? "");
      const { data: p } = await db.from("pass").select("utilisateur").eq("transaction_id", tx).maybeSingle();
      if (!p || p.utilisateur !== uid) return json({ message: "Transaction introuvable." }, 404);
      const maj = await verifierTransaction(tx);
      return json({ statut: maj?.statut ?? "en_attente", fin: maj?.fin ?? null });
    }

    return json({ message: "Action inconnue." }, 400);
  } catch (e) {
    return json({ message: "Erreur du serveur : " + (e as Error).message }, 500);
  }
});
