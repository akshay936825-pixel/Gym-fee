/** Gym Hisab safe snapshot bridge. Deploy as Web App, execute as the spreadsheet owner. */
const SHEET_ID = "1i5gWu-HzcSVTJep6rgxYi5jNOkBi3pTIgoYiYHnll5Y";
const SYNC_TOKEN = "093af4624f9fd40c6d20090e55d904598fc83cb46f16caeaab48bcd471401236";
const book = () => SpreadsheetApp.openById(SHEET_ID);
const json = value => ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON);

function doGet() {
  // Deliberately do not expose member/payment data through an unauthenticated GET endpoint.
  return json({ ok: true, service: "Gym Hisab sync", message: "POST an authenticated syncSnapshot request." });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
    const body = JSON.parse((e && e.postData && e.postData.contents) || "{}");
    if (body.action !== "syncSnapshot" || body.token !== SYNC_TOKEN) return json({ ok: false, error: "Unauthorized request" });
    const d = body.data || {};
    const ss = book();
    // Existing tabs retain their original columns and all historical rows. Rows are updated by stable ID.
    upsert(ss, "Members", ["ID", "Name", "Phone", "Joined", "Fee"], (d.members || []).map(x => [x.id, x.name, x.phone || "", x.joined_on || "", memberFee(ss, d.memberships || [], x.id)]));
    ensureMemberFormulas(ss.getSheetByName("Members"));
    // Preserve the legacy Payments A:H layout used by the owner's existing workbook.
    upsert(ss, "Payments", ["ID", "Member ID", "Name", "Month", "Amount", "Date", "Mode", "Note"], (d.payments || []).map(x => [x.id, x.member_id, memberName(d.members || [], x.member_id), String(x.paid_on || "").slice(0, 7), (Number(x.amount_paise || 0) / 100) * (x.kind === "refund" ? -1 : 1), x.paid_on || "", x.mode || "", x.note || ""]));
    upsert(ss, "Memberships", ["ID", "Member ID", "Plan", "Start", "End", "Price (INR)", "Discount (INR)", "Status"], (d.memberships || []).map(x => [x.id, x.member_id, x.plan_name, x.start_on, x.end_on, Number(x.price_paise || 0) / 100, Number(x.discount_paise || 0) / 100, x.status || "active"]));
    upsert(ss, "Expenses", ["ID", "Date", "Category", "Amount (INR)", "Note", "Deleted"], (d.expenses || []).map(x => [x.id, x.spent_on, x.category, Number(x.amount_paise || 0) / 100, x.note || "", !!x.deleted]));
    upsert(ss, "Plans", ["ID", "Name", "Duration (months)", "Price (INR)", "Active"], (d.plans || []).map(x => [x.id, x.name, x.duration_months, Number(x.price_paise || 0) / 100, !!x.active]));
    upsert(ss, "Attendance", ["ID", "Member ID", "Date", "Present"], (d.attendance || []).map(x => [x.id, x.member_id, x.attended_on, !!x.present]));
    // Archive state lives in column J so existing formulas in F:I are not overwritten.
    const memberSheet = ss.getSheetByName("Members");
    if (memberSheet && !memberSheet.getRange(1, 10).getValue()) memberSheet.getRange(1, 10).setValue("Archived");
    const archived = (d.members || []).map(x => [x.id, !!x.archived]);
    updateArchiveColumn(memberSheet, archived);
    SpreadsheetApp.flush();
    return json({ ok: true, protocol: "gym-hisab-v2", counts: { members: (d.members || []).length, payments: (d.payments || []).length, memberships: (d.memberships || []).length, expenses: (d.expenses || []).length, plans: (d.plans || []).length, attendance: (d.attendance || []).length } });
  } catch (err) {
    return json({ ok: false, error: String(err && err.message || err) });
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

function memberName(members, id) {
  const found = members.find(m => String(m.id) === String(id));
  return found ? found.name : "";
}
function memberFee(ss, memberships, memberId) {
  const list = memberships.filter(x => String(x.member_id) === String(memberId));
  if (list.length) {
    list.sort((a, b) => String(b.start_on).localeCompare(String(a.start_on)));
    return Math.max(0, Number(list[0].price_paise || 0) - Number(list[0].discount_paise || 0)) / 100;
  }
  // MemberE has no fee field. Preserve the owner's existing fee when present; use the legacy default for new rows.
  const sh = ss.getSheetByName("Members");
  if (sh && sh.getLastRow() >= 2) {
    const ids = sh.getRange(2, 1, sh.getLastRow() - 1, 1).getDisplayValues().flat();
    const index = ids.indexOf(String(memberId));
    if (index >= 0) return sh.getRange(index + 2, 5).getValue();
  }
  return 500;
}

/** ID-keyed upsert: no clear(), no deleteRow(), and no rewriting of rows absent from this snapshot. */
function upsert(ss, tabName, headers, rows) {
  let sh = ss.getSheetByName(tabName);
  if (!sh) sh = ss.insertSheet(tabName);
  if (sh.getLastRow() === 0) sh.getRange(1, 1, 1, headers.length).setValues([headers]);
  if (!rows.length) return;
  const idToRow = new Map();
  const last = sh.getLastRow();
  if (last >= 2) {
    sh.getRange(2, 1, last - 1, 1).getDisplayValues().forEach((r, i) => {
      const id = String(r[0] || "");
      if (id && !idToRow.has(id)) idToRow.set(id, i + 2);
    });
  }
  const append = [];
  rows.forEach(row => {
    const id = String(row[0] || "");
    if (!id) return;
    const existing = idToRow.get(id);
    if (existing) sh.getRange(existing, 1, 1, row.length).setValues([row]);
    else append.push(row);
  });
  if (append.length) sh.getRange(sh.getLastRow() + 1, 1, append.length, append[0].length).setValues(append);
}

function ensureMemberFormulas(sh) {
  if (!sh || sh.getLastRow() < 2) return;
  const count = sh.getLastRow() - 1;
  const formulas = sh.getRange(2, 6, count, 1).getFormulas().flat();
  formulas.forEach((formula, i) => {
    if (formula) return; // Never replace an owner's existing formula.
    const r = i + 2;
    sh.getRange(r, 6, 1, 4).setFormulas([[
      `=SUMPRODUCT((Payments!$B$2:$B$2000=A${r})*(Payments!$D$2:$D$2000=TEXT(TODAY(),"yyyy-mm"))*Payments!$E$2:$E$2000)`,
      `=MAX(0,E${r}-F${r})`,
      `=IF(F${r}>=E${r},"Paid",IF(F${r}>0,"Partial","Unpaid"))`,
      `=SUMPRODUCT((Payments!$B$2:$B$2000=A${r})*Payments!$E$2:$E$2000)`
    ]]);
  });
}

function updateArchiveColumn(sh, rows) {
  if (!sh || !rows.length) return;
  const last = sh.getLastRow();
  if (last < 2) return;
  const ids = sh.getRange(2, 1, last - 1, 1).getDisplayValues().flat();
  const states = new Map(rows.map(r => [String(r[0]), r[1]]));
  const current = sh.getRange(2, 10, last - 1, 1).getValues().flat();
  const values = ids.map((id, i) => [states.has(String(id)) ? states.get(String(id)) : current[i]]);
  sh.getRange(2, 10, values.length, 1).setValues(values);
}
