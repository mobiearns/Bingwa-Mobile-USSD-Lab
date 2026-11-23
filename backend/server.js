import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import Database from "better-sqlite3";

const here = path.dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT || 8080);
const adminKey = process.env.BINGWA_SMS_ADMIN_KEY || "";
const providerToken = process.env.TALKSASA_API_TOKEN || "";
const senderDefault = process.env.TALKSASA_SENDER_ID || "";
const apiBase = "https://bulksms.talksasa.com/api/v3";
const dbPath = process.env.SQLITE_PATH || path.join(here, "data", "sms.sqlite");
fs.mkdirSync(path.dirname(dbPath), { recursive: true });
const db = new Database(dbPath);
db.pragma("journal_mode = WAL");
db.exec(`
CREATE TABLE IF NOT EXISTS sms_jobs (
 id TEXT PRIMARY KEY, idempotency_key TEXT UNIQUE NOT NULL, recipients TEXT NOT NULL,
 message TEXT NOT NULL, sender_id TEXT NOT NULL, mode TEXT NOT NULL,
 provider_uid TEXT, status TEXT NOT NULL, provider_response TEXT,
 error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_log (
 id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, actor TEXT NOT NULL,
 detail TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS request_limits (
 bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, reset_at INTEGER NOT NULL
);
`);
const insertJob = db.prepare("INSERT INTO sms_jobs(id,idempotency_key,recipients,message,sender_id,mode,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)");
const updateJob = db.prepare("UPDATE sms_jobs SET provider_uid=?,status=?,provider_response=?,error=?,updated_at=? WHERE id=?");
const audit = db.prepare("INSERT INTO audit_log(action,actor,detail,created_at) VALUES(?,?,?,?)");
const now = () => new Date().toISOString();
const json = (res, code, data) => { res.writeHead(code, { "content-type":"application/json; charset=utf-8", "cache-control":"no-store" }); res.end(JSON.stringify(data)); };
const safeEqual = (a,b) => { const x=Buffer.from(a), y=Buffer.from(b); return x.length===y.length && crypto.timingSafeEqual(x,y); };
const normalizePhone = raw => {
 const d=String(raw||"").replace(/[^0-9]/g,"");
 const n=d.startsWith("254")?d:d.startsWith("0")?"254"+d.slice(1):d.length===9?"254"+d:"";
 return /^254[17][0-9]{8}$/.test(n)?n:null;
};
const sanitize = value => String(value??"").replace(/[\r\n\t]/g," ").slice(0,500);
function rateLimit(key, max=40, windowMs=60000) {
 const t=Date.now(), row=db.prepare("SELECT count,reset_at FROM request_limits WHERE bucket=?").get(key);
 if(!row || row.reset_at<=t) db.prepare("INSERT INTO request_limits(bucket,count,reset_at) VALUES(?,?,?) ON CONFLICT(bucket) DO UPDATE SET count=excluded.count,reset_at=excluded.reset_at").run(key,1,t+windowMs);
 else if(row.count>=max) return false;
 else db.prepare("UPDATE request_limits SET count=count+1 WHERE bucket=?").run(key);
 return true;
}
async function provider(pathname, method="GET", body=null) {
 if(!providerToken) { const e=new Error("TALKSASA_API_TOKEN is not configured on the backend"); e.status=503; throw e; }
 const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),15000);
 try {
  const response=await fetch(apiBase+pathname,{method,headers:{Authorization:"Bearer "+providerToken,Accept:"application/json","Content-Type":"application/json"},body:body?JSON.stringify(body):undefined,signal:controller.signal});
  const raw=await response.text(); let data={}; try { data=raw?JSON.parse(raw):{}; } catch { data={raw:raw.slice(0,1000)}; }
  if(!response.ok) { const e=new Error(sanitize(data.message||data.error||data.errors||("TalkSasa HTTP "+response.status))); e.status=response.status; e.provider=data; throw e; }
  return {httpStatus:response.status,data};
 } catch(e) {
  if(e.name==="AbortError") { const x=new Error("TalkSasa request timed out"); x.status=504; throw x; }
  throw e;
 } finally { clearTimeout(timer); }
}
function getUid(data) {
 const d=data?.data??data;
 return String(d?.uid??d?.message_uid??d?.id??d?.message_id??"");
}
function providerState(data, httpStatus) {
 const d=data?.data??data, s=String(d?.status??d?.state??"").toLowerCase();
 if(["delivered","delivery_success"].includes(s)) return "delivered";
 if(["failed","rejected","undelivered","error"].includes(s)) return "failed";
 if(["sent","submitted"].includes(s)) return "sent";
 if(["queued","pending","processing"].includes(s)) return "queued";
 return httpStatus===202 || httpStatus===200 || httpStatus===201 ? "accepted" : "unknown";
}
async function readBody(req) {
 let raw=""; for await(const chunk of req) { raw+=chunk; if(raw.length>1024*1024) throw Object.assign(new Error("Request too large"),{status:413}); }
 try { return raw?JSON.parse(raw):{}; } catch { throw Object.assign(new Error("Invalid JSON body"),{status:400}); }
}
function auth(req,res) {
 if(!adminKey) { json(res,503,{error:"BINGWA_SMS_ADMIN_KEY is not configured"}); return false; }
 const supplied=(req.headers.authorization||"").replace(/^Bearer\s+/i,"");
 if(!safeEqual(supplied,adminKey)) { json(res,401,{error:"Unauthorized"}); return false; }
 const actor=crypto.createHash("sha256").update(supplied).digest("hex").slice(0,12);
 if(!rateLimit("admin:"+actor,60)) { json(res,429,{error:"Rate limit exceeded"}); return false; }
 return actor;
}
function auditEvent(action, actor, detail) { audit.run(action,actor,sanitize(detail),now()); }
async function handler(req,res) {
 const url=new URL(req.url,"http://localhost");
 if(req.method==="GET" && url.pathname==="/health") return json(res,200,{ok:true,service:"bingwa-talksasa-proxy",configured:!!(adminKey&&providerToken)});
 const actor=auth(req,res); if(!actor) return;
 try {
  if(req.method==="GET" && url.pathname==="/v1/sms/config") return json(res,200,{provider:"TALKSASA",configured:!!providerToken,sender_id:senderDefault?senderDefault:"",token_configured:!!providerToken});
  if(req.method==="GET" && url.pathname==="/v1/sms/me") { const r=await provider("/me"); auditEvent("account_profile",actor,"profile retrieved"); return json(res,200,r.data); }
  if(req.method==="GET" && url.pathname==="/v1/sms/balance") { const r=await provider("/balance"); auditEvent("balance",actor,"balance retrieved"); return json(res,200,r.data); }
  if(req.method==="GET" && url.pathname==="/v1/sms/dashboard") {
   const counts=db.prepare("SELECT status,COUNT(*) n FROM sms_jobs GROUP BY status").all();
   const totals=Object.fromEntries(counts.map(x=>[x.status,x.n]));
   const recent=db.prepare("SELECT id,recipients,message,sender_id,mode,provider_uid,status,error,created_at,updated_at FROM sms_jobs ORDER BY created_at DESC LIMIT 30").all();
   let balance=null, providerRecords=null;
   try { balance=(await provider("/balance")).data; } catch(e) { balance={error:sanitize(e.message)}; }
   try { providerRecords=(await provider("/sms")).data; } catch(e) { providerRecords={error:sanitize(e.message)}; }
   return json(res,200,{totals:{submitted:Object.values(totals).reduce((a,b)=>a+b,0),accepted:totals.accepted||0,queued:totals.queued||0,sent:totals.sent||0,delivered:totals.delivered||0,failed:totals.failed||0},balance,providerRecords,recent});
  }
  if(req.method==="GET" && url.pathname==="/v1/sms/history") {
   const rows=db.prepare("SELECT * FROM sms_jobs ORDER BY created_at DESC LIMIT 200").all();
   return json(res,200,{items:rows});
  }
  if(req.method==="POST" && url.pathname==="/v1/sms/send") {
   const b=await readBody(req), raw=Array.isArray(b.recipients)?b.recipients:String(b.recipient||"").split(",");
   const recipients=[...new Set(raw.map(normalizePhone).filter(Boolean))];
   if(!recipients.length || recipients.length!==raw.length) return json(res,422,{error:"All recipients must be valid Kenyan numbers in 254XXXXXXXXX format"});
   if(recipients.length>500) return json(res,422,{error:"Maximum 500 recipients per request"});
   const message=String(b.message||"").trim(), sender=sanitize(b.sender_id||senderDefault), type=b.type==="hashed"?"hashed":"plain";
   if(!message || message.length>1000) return json(res,422,{error:"Message is required and must be at most 1000 characters"});
   if(!sender) return json(res,422,{error:"An approved sender_id is required"});
   if(type==="hashed" && recipients.length!==1) return json(res,422,{error:"Hashed SMS supports one recipient per request"});
   const key=String(req.headers["idempotency-key"]||b.idempotency_key||"").trim();
   if(!key || key.length>128) return json(res,400,{error:"Idempotency-Key header is required"});
   const existing=db.prepare("SELECT * FROM sms_jobs WHERE idempotency_key=?").get(key);
   if(existing) return json(res,200,{duplicate:true,id:existing.id,uid:existing.provider_uid,status:existing.status,job:existing});
   const id=crypto.randomUUID(), created=now();
   insertJob.run(id,key,JSON.stringify(recipients),message,sender,type,"submitting",null,created,created);
   const endpoint=type==="hashed"?"/sms/send-hashed":"/sms/send";
   const payload=type==="hashed"?{recipient:recipients[0],recipient_format:"plain",sender_id:sender,message}:{recipient:recipients.join(","),sender_id:sender,type:"plain",message};
   try {
    const r=await provider(endpoint,"POST",payload), uid=getUid(r.data), status=providerState(r.data,r.httpStatus);
    updateJob.run(uid||null,status,JSON.stringify(r.data).slice(0,8000),null,now(),id);
    auditEvent("sms_submit",actor,JSON.stringify({id,recipient_count:recipients.length,type,status,uid}));
    return json(res,r.httpStatus===202?202:200,{id,uid,status,recipient_count:recipients.length,response:r.data});
   } catch(e) {
    const status=e.status===402?"insufficient_balance":e.status===401||e.status===403?"auth_failed":"failed";
    updateJob.run(null,status,null,sanitize(e.message),now(),id);
    auditEvent("sms_error",actor,JSON.stringify({id,status,error:e.message}));
    return json(res,e.status||502,{id,status,error:sanitize(e.message),provider_error:e.provider||null});
   }
  }
  if(req.method==="GET" && /^\/v1\/sms\/message\/[^/]+$/.test(url.pathname)) {
   const uid=decodeURIComponent(url.pathname.split("/").pop());
   const r=await provider("/sms/"+encodeURIComponent(uid));
   const status=providerState(r.data,r.httpStatus);
   db.prepare("UPDATE sms_jobs SET status=?,provider_response=?,updated_at=? WHERE provider_uid=?").run(status,JSON.stringify(r.data).slice(0,8000),now(),uid);
   return json(res,200,{uid,status,response:r.data});
  }
  if(req.method==="GET" && url.pathname==="/v1/sms/provider-records") return json(res,200,(await provider("/sms")).data);
  if(req.method==="POST" && url.pathname==="/v1/sms/refresh-status") {
   const rows=db.prepare("SELECT id,provider_uid FROM sms_jobs WHERE provider_uid IS NOT NULL AND status IN (?,?,?,?) ORDER BY created_at DESC LIMIT 100").all("accepted","queued","sent","unknown");
   const results=[];
   for(const row of rows) {
    try {
     const r=await provider("/sms/"+encodeURIComponent(row.provider_uid));
     const status=providerState(r.data,r.httpStatus);
     db.prepare("UPDATE sms_jobs SET status=?,provider_response=?,updated_at=? WHERE id=?").run(status,JSON.stringify(r.data).slice(0,8000),now(),row.id);
     results.push({id:row.id,uid:row.provider_uid,status});
    } catch(e) { results.push({id:row.id,uid:row.provider_uid,error:sanitize(e.message)}); }
   }
   return json(res,200,{refreshed:results.length,results});
  }
  if(req.method==="GET" && url.pathname==="/v1/sms/contacts") return json(res,200,(await provider("/contacts")).data);
  if(req.method==="POST" && url.pathname==="/v1/sms/contacts") return json(res,501,{error:"TalkSasa contact-group creation request schema is not included in the supplied API specification; refusing to guess its payload."});
  const addContact=url.pathname.match(/^\/v1\/sms\/contacts\/([^/]+)\/store$/);
  if(req.method==="POST" && addContact) return json(res,501,{error:"TalkSasa contact-add request schema is not included in the supplied API specification; refusing to guess its payload."});
  const groupContacts=url.pathname.match(/^\/v1\/sms\/contacts\/([^/]+)\/all$/);
  if(req.method==="POST" && groupContacts) return json(res,200,(await provider("/contacts/"+encodeURIComponent(groupContacts[1])+"/all","POST",await readBody(req))).data);
  if(req.method==="POST" && url.pathname==="/v1/sms/campaign") {
   const b=await readBody(req), groupId=String(b.contact_list_id||"").trim(), sender=sanitize(b.sender_id||senderDefault), message=String(b.message||"").trim();
   if(!groupId) return json(res,422,{error:"contact_list_id is required by the supplied campaign example; the alternative recipient form is not confirmed"});
   if(!sender || !message || message.length>1000) return json(res,422,{error:"sender_id and a message of 1–1000 characters are required"});
   const key=String(req.headers["idempotency-key"]||"").trim();
   if(!key || key.length>128) return json(res,400,{error:"Idempotency-Key header is required"});
   const existing=db.prepare("SELECT * FROM sms_jobs WHERE idempotency_key=?").get(key);
   if(existing) return json(res,200,{duplicate:true,id:existing.id,uid:existing.provider_uid,status:existing.status,job:existing});
   const id=crypto.randomUUID(), created=now();
   insertJob.run(id,key,JSON.stringify(["group:"+groupId]),message,sender,"campaign","submitting",created,created);
   try {
    const r=await provider("/sms/campaign","POST",{contact_list_id:groupId,sender_id:sender,type:"plain",message});
    const uid=getUid(r.data), status=providerState(r.data,r.httpStatus);
    updateJob.run(uid||null,status,JSON.stringify(r.data).slice(0,8000),null,now(),id);
    auditEvent("campaign_submit",actor,JSON.stringify({id,groupId,status,uid}));
    return json(res,r.httpStatus===202?202:200,{id,uid,status,response:r.data});
   } catch(e) {
    updateJob.run(null,"failed",null,sanitize(e.message),now(),id);
    auditEvent("campaign_error",actor,JSON.stringify({id,error:e.message}));
    return json(res,e.status||502,{id,status:"failed",error:sanitize(e.message)});
   }
  }
  if(req.method==="GET" && url.pathname==="/v1/sms/audit") return json(res,200,{items:db.prepare("SELECT action,actor,detail,created_at FROM audit_log ORDER BY id DESC LIMIT 200").all()});
  return json(res,404,{error:"Not found"});
 } catch(e) {
  const code=e.status||502; auditEvent("api_error",actor,JSON.stringify({path:url.pathname,status:code,error:e.message}));
  return json(res,code,{error:sanitize(e.message),provider_error:e.provider||null});
 }
}
http.createServer((req,res)=>handler(req,res).catch(e=>json(res,500,{error:"Internal server error"}))).listen(port,"0.0.0.0",()=>console.log("Bingwa SMS backend listening on "+port));
