from pathlib import Path
import re
from xml.sax.saxutils import escape
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph, Table, TableStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.graphics.shapes import Drawing, Rect, String, Line, Polygon
from reportlab.graphics import renderPDF, renderSVG
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output' / 'pdf'
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'diagrams').mkdir(exist_ok=True)
pdfmetrics.registerFont(TTFont('Arial', 'C:/Windows/Fonts/arial.ttf'))
pdfmetrics.registerFont(TTFont('ArialBold', 'C:/Windows/Fonts/arialbd.ttf'))
pdfmetrics.registerFontFamily('Arial', normal='Arial', bold='ArialBold', italic='Arial', boldItalic='ArialBold')
INK = colors.HexColor('#172B3A')
MUTED = colors.HexColor('#526575')
TEAL = colors.HexColor('#0A6A67')
RULE = colors.HexColor('#CBD5DC')
BLUE = colors.HexColor('#E8F1FC')
GREEN = colors.HexColor('#E5F5EC')
PAGE_W, PAGE_H = A4
MARGIN = 42
WIDTH = PAGE_W - 2*MARGIN
styles = {
    'body': ParagraphStyle('body', fontName='Arial', fontSize=9.6, leading=12.7, textColor=INK, spaceAfter=6),
    'small': ParagraphStyle('small', fontName='Arial', fontSize=8.2, leading=10.6, textColor=MUTED),
    'heading': ParagraphStyle('heading', fontName='ArialBold', fontSize=11.3, leading=14, textColor=TEAL, spaceAfter=5),
    'cell': ParagraphStyle('cell', fontName='Arial', fontSize=8.7, leading=11.3, textColor=INK),
}

ARCH_MD = '''flowchart TB
    T["EXISTING: Template service + DB"]
    subgraph REGION["Firm region - repeat per approved region"]
        A["NEW: API + event process"]
        M[("NEW: Regional metadata store")]
        Q["NEW: Slow-work queue"]
        W["NEW: Java worker process"]
        E["EXISTING: Engagement service + firm DBs"]
        A <-->|Query and project| M
        M -->|Recoverable dispatch| Q
        Q --> W
        W -->|Controlled load: about 1 minute| E
        W -->|Revision-checked results| M
        E -->|Durable state and decision events| A
    end
    T -->|Durable publication and withdrawal events| A
    classDef old fill:#E8F1FC,stroke:#3472AD,color:#172B3A
    classDef new fill:#E5F5EC,stroke:#247452,color:#172B3A
    class T,E old
    class A,M,Q,W new'''
WORK_MD = '''flowchart LR
    A["Claim and check task"] --> B["Obtain capacity and circuit permission"]
    B --> C["Call slow service"]
    C --> D["Persist result and completion; then ack"]
    C -.->|Resolve uncertainty; defer or retry with backoff| A
    classDef new fill:#E5F5EC,stroke:#247452,color:#172B3A
    class A,B,C,D new'''

def label(d, x, y, text, size=8.2, bold=False, anchor='middle', color=INK):
    d.add(String(x, y, text, fontName='ArialBold' if bold else 'Arial', fontSize=size, fillColor=color, textAnchor=anchor))

def box(d, x, y, w, h, lines, old=False):
    d.add(Rect(x, y, w, h, rx=4, ry=4, fillColor=BLUE if old else GREEN, strokeColor=colors.HexColor('#3472AD') if old else colors.HexColor('#247452'), strokeWidth=.8))
    yy = y + h/2 + (len(lines)-1)*5 - 3
    for i, line in enumerate(lines):
        label(d, x+w/2, yy-i*10, line, size=8.3, bold=(i==0))

def arrow(d, pts, dashed=False):
    for p, q in zip(pts, pts[1:]):
        ln=Line(*p,*q,strokeColor=MUTED,strokeWidth=.9)
        if dashed: ln.strokeDashArray=[3,2]
        d.add(ln)
    (x0,y0),(x,y)=pts[-2:]
    dx,dy=x-x0,y-y0
    length=(dx*dx+dy*dy)**.5
    ux,uy=dx/length,dy/length
    d.add(Polygon([x,y,x-5*ux+2.3*uy,y-5*uy-2.3*ux,x-5*ux-2.3*uy,y-5*uy+2.3*ux],fillColor=MUTED,strokeColor=MUTED))

def architecture():
    d=Drawing(WIDTH,267)
    d.add(Rect(0,5,WIDTH,197,rx=6,ry=6,fillColor=None,strokeColor=RULE,strokeWidth=.8))
    label(d,WIDTH-12,186,'FIRM REGION - REPEAT PER APPROVED REGION',8,True,'end',MUTED)
    box(d,10,222,190,38,['Template service + DB','Existing source of template truth'],True)
    label(d,286,249,'EXISTING',8,True,'start',colors.HexColor('#3472AD'))
    label(d,394,249,'NEW',8,True,'start',colors.HexColor('#247452'))
    label(d,286,234,'Blue',8,False,'start')
    label(d,394,234,'Green',8,False,'start')
    box(d,10,125,190,43,['API + event process','Queries, catalog and projections'])
    box(d,280,125,WIDTH-290,43,['Regional metadata store','Inbox, catalog, jobs and read model'])
    box(d,10,37,110,42,['Engagement system','Service + firm DBs'],True)
    box(d,150,37,150,42,['Java worker process','Fan-out, slow calls, summaries'])
    box(d,330,37,WIDTH-340,42,['Slow-work queue','Verification tasks'])
    arrow(d,[(105,222),(105,168)])
    label(d,150,211,'Durable events',8)
    arrow(d,[(200,148),(280,148)])
    label(d,240,156,'Query / project',7.7)
    arrow(d,[(420,125),(420,79)])
    label(d,455,100,'Dispatch',7.8)
    arrow(d,[(330,58),(300,58)])
    arrow(d,[(150,58),(120,58)])
    label(d,154,22,'Controlled load: about 1 minute',7.8)
    arrow(d,[(65,79),(65,125)])
    label(d,101,101,'State events',7.5)
    arrow(d,[(225,79),(225,105),(320,105),(320,125)])
    label(d,268,112,'Verified results',7.5)
    return d

def worker_diagram():
    d=Drawing(WIDTH,94)
    box(d,0,40,109,43,['Claim task','Check revision / duplicates'])
    box(d,132,40,111,43,['Admit call','Capacity + circuit breaker'])
    box(d,266,40,101,43,['Load file','About one minute'])
    box(d,390,40,WIDTH-390,43,['Persist, then ack','Result + job completion'])
    for a,b in [(109,132),(243,266),(367,390)]: arrow(d,[(a,61),(b,61)])
    arrow(d,[(316,40),(316,13),(54,13),(54,40)],True)
    label(d,185,21,'Resolve ambiguity; defer or retry with backoff',7.7)
    return d

pages = [
{
'title':'Template update notifications',
'subtitle':'Caseware | Architecture design | 16 November 2026',
'blocks':[
('p','<b>Decision.</b> Add one regional application with an API/event process and a Java worker, a metadata store, and a slow-work queue. Keep existing services authoritative. Use CQRS to serve update indicators from derived metadata while isolating one-minute engagement loads. Publishing a template does not invalidate a verified engagement baseline.'),
('h','1. Scope, boundaries and ownership'),
('p','Support 4,000 firms, 800,000 active files and 40 products, with roughly one publication per product per week. The median firm has 40 files; the largest has about 40,000. Users need pending-update indicators, understandable change summaries and accept/decline decisions. Applying template content is outside scope.'),
('diagram','architecture'),
('caption','Figure 1. One codebase, two processes per region. Events bypass the slow queue; durable dispatch is a worker module. Summary objects are stored separately, with references in the metadata store.'),
('p','<b>Ownership.</b> The template service owns content, versions and compatibility policy. The engagement service owns applied-version metadata, offers and decisions. The regional catalog/read model is rebuildable. Store exact summary evidence as immutable objects. Projection, target selection, fan-out, admission and summary generation are internal modules, not additional services.'),
('p','<b>Deployment and privacy.</b> A practical AWS mapping is API/worker processes on ECS, a regional relational metadata store, SQS for slow tasks, and S3 for evidence. Keep firm IDs, metadata, jobs, decisions, logs and backups in their approved region; enforce tenant authorization and encryption. Only non-firm-specific template content enters the summary model. Regional summary caches reuse artifacts across firms.'),
('p','<b>Fast-path contract.</b> Source outbox relays deliver directly to the API/event process. Acknowledge after atomically committing the deduplicated inbox entry, projection and work intents. Separate execution/connection budgets protect queries and events from slow calls; summary generation runs in a separate bounded worker pool. Shared database contention remains a monitored risk.'),
]},
{
'title':'Correctness and safe evolution',
'subtitle':'Version policy, durable processing and migration',
'blocks':[
('h','2. Version and decision semantics'),
('p','Content owners maintain a revisioned, ordered recommendation list per product/market/branch. Each target explicitly declares allowed source versions and upgrade relationships. Select the highest-priority active compatible target; version IDs are opaque. Cross-branch/market moves require explicit permission. Ties or contradictory policy block the affected offer. Withdrawal selects the next approved target, never an unapproved downgrade.'),
('p','An offer binds engagement, actual baseline, target, expected engagement revision, catalog/policy revision and displayed summary artifact. Declining pins that baseline/target pair; regenerating text cannot bypass the decline. Thus declining v3-to-v5 still permits v3-to-v7, summarized directly from v3. A newer eligible target supersedes an older pending offer. Withdrawals revoke offers, including accepted-but-unapplied ones, while retaining history.'),
('p','Commands validate authorization and authoritative state with expected revisions and idempotency keys. Acceptance records <b>ACCEPTED_PENDING_APPLICATION</b>; only confirmed application changes the baseline. If validation needs an unavailable slow load, return pending/retryable status. A subsequent query must reach the confirmed decision revision or report synchronization pending. Withdrawal races are resolved by revocation when observed and revalidation before application; cross-system atomic ordering is not claimed.'),
('h','3. Worker and failure recovery'),
('diagram','worker'),
('caption','Figure 2. At-least-once delivery with durable progress. A local timeout is not evidence that remote execution has stopped.'),
('p','Persist a resumable fan-out run; enumerate candidates in bounded pages and atomically save unique tasks plus cursor advancement. Dispatch through a durable outbox. Workers claim tasks atomically, renew leases, reject stale results by source revision, and persist completion before acknowledging. Repeated messages and replayed pages are safe. Fair admission across firms and bounded backfill capacity prevent a 40,000-file firm from monopolizing the dependency.'),
('p','Enforce shared active-call and start-rate limits using the dependency owner\'s allocation, reserving interactive capacity. A circuit breaker pauses dependency failures and allows limited recovery probes. One retry layer persists bounded exponential-backoff/jitter attempts; capacity deferrals are not execution failures. Permanent file errors end explicitly. Retain operation IDs and capacity after ambiguous timeouts/crashes until remote completion, cancellation or an enforceable execution limit is established. Without that contract, strict capacity guarantees trade away availability.'),
('h','4. No-downtime migration'),
('p','Enable backward-compatible metadata hooks and reliable events before checkpointed backfill. Obtain a pageable inventory and recover the actual applied version through the one-minute loader; creation-time metadata is insufficient. Merge live events and backfill by source revision. Unknown files, including those with unknown product, stay in regional backfill and display CHECKING/UNKNOWN. Shadow-compare results, enable firms gradually and roll back visibility with feature flags. Reconcile inventory coverage, event gaps, incomplete jobs and ambiguous operations; replay events before reloading files.'),
]},
{
'title':'Scale, operating cost and service levels',
'subtitle':'Explicit planning assumptions; no unverified budget claim',
'blocks':[
('h','5. Selective verification and capacity'),
('p','A publication updates the small catalog immediately. Reload only missing/inconsistent baselines, unresolved event gaps, incomplete verifications and a fair 1% weekly reconciliation sample. Healthy state events refresh metadata without another load. The Java worker still performs the required slow call for selected files; initial backfill selects all unverified files. Sampling detects drift probabilistically, not within a guaranteed week; detected drift expands reconciliation and its budget.'),
('table',[
['Capacity calculation','Ideal result'],
['800,000 files x 1 minute','800,000 call-minutes for initial backfill'],
['Backfill with C = 100 dedicated slots','5.56 days, excluding retries and other traffic'],
['20,000-file average product / C = 100','3.33 hours for a full reload (stress case)'],
['800,000 / (7 x 24 x 60)','79.4 occupied slots for weekly full reloads'],
['Concurrency C; duration L; start limit R','Throughput <= min(R, C/L) calls/second'],
]),
('p','C = 100 is illustrative, not an authorized allocation. If regions share the dependency, regional quotas must sum to its global allowance. More workers do not create downstream capacity.'),
('h','6. Quantified monthly cost model'),
('p','Assume 52/12 weeks/month, 1% weekly reads, 5% extra attempts and no additional gap repairs. For summaries assume 10 active baselines per publication, 2 locales, 3 regional caches, 10% extra generation attempts, and 3,000 input/500 output tokens per attempt. These are sizing inputs, not measured traffic or prices.'),
('table',[
['Monthly workload','Calculation / result'],
['Slow attempts A','800,000 x 1% x 52/12 x 1.05 = 36,400'],
['Downstream duration','36,400 minutes = 606.67 accumulated call-hours'],
['Queue / base metadata operations','3A = 109,200 / 5A = 182,000; overhead is extra'],
['Summary attempts G','40 x 52/12 x 10 x 2 x 3 x 1.10 = 11,440'],
['Inference tokens','34.32 million input / 5.72 million output'],
]),
('p','<b>Monthly cost =</b> regional baseline infrastructure + worker compute + 36,400 x downstream price/minute + 0.1092 x queue price/million + 34.32 x input-token price/million + 5.72 x output-token price/million + database operations, storage, transfer and observability. Include idle compute, query polling, source-event handling, renewals, retries, object requests and retained sources/backups. Avoid charging provisioned database operations twice. Call-hours are not billed worker-hours.'),
('p','At an illustrative 50 KB/artifact, generation adds 0.572 GB/month; 12 months retain 6.864 GB before source snapshots/backups. Retention follows agreed evidence requirements. Gap repair adds jobs to A; full weekly reloads would produce 3.64 million attempts with the same retry factor. Initial migration adds 840,000 attempts at 5% overhead, roughly 2.52 million basic queue operations and 4.2 million base mutations. Charge migration in the months it occurs. Confirm unit rates and X before claiming either recurring or migration-month compliance.'),
('h','7. User-visible SLOs and observability'),
('p','Propose p95 <= 5 seconds from publication commit to the queryable personalized indicator for already indexed files with healthy event progress at publication time. Fix that cohort; later failures count as misses. Report unknown coverage separately. Open screens poll every 5 seconds; independently target p95 <= 10 seconds to visible change. Summary readiness is separate. Measure end-to-end lag, event gaps, coverage, queue age, per-firm wait, active/ambiguous calls, stale results, throttling and cost; alert on freshness breaches, stalled progress or capacity violations.'),
]},
{
'title':'Trustworthy summaries and delivery scope',
'subtitle':'Evidence that remains defensible months after a decision',
'blocks':[
('h','8. Summary generation and evaluation'),
('p','Use the existing comparator on the actual baseline and eligible target. Reuse immutable artifacts by product, baseline/target content hashes, locale and generator/comparator revision. A cache miss records one deduplicated generation intent and returns SUMMARY_PENDING; the indicator never waits for inference. Regional caching avoids a cross-region coordination service.'),
('p','<b>Generate.</b> Normalize JSON changes into stable IDs, operations, before/after values and domain labels. Chunk large diffs while retaining a complete manifest. Ask the model for structured, plain-language claims linked to change IDs and a disposition for each change. Restrict input to template content; prohibit invented accounting or regulatory conclusions.'),
('p','<b>Validate.</b> Check schema, references, exact fields where mechanically possible, version identity, and coverage of material changes labeled by domain rules or experts. The model cannot silently classify omitted changes as immaterial. Valid references alone do not establish semantic fidelity. Release a generator revision only after an expert-reviewed corpus achieves zero unsupported claims, correct references and full labeled-material-change coverage, with acceptable readability. Include numeric edits, deletions, branches, cumulative changes, large diffs and supported languages; finite-corpus success is not a production guarantee.'),
('p','<b>Fail safely.</b> Use a proposed 60-second deadline after an attempt starts, separate from queue delay. On timeout or invalid output, render approved plain-language templates for supported change types. If material changes cannot be explained faithfully, show SUMMARY_UNAVAILABLE and request review rather than exposing raw JSON or an unrelated summary. Sample production output and disable faulty generator revisions. Decisions require the exact readable artifact the user reviewed.'),
('p','<b>Preserve evidence.</b> Retain exact text, source versions/diff and hashes, model/configuration/prompt/comparator versions, validation results and timestamps. A regenerated summary is a new artifact. Store presentation receipts, offer identity, actor and decision history in the firm\'s region, referencing the immutable artifact. A November review retrieves the March evidence; it does not rerun the model and assume the same output.'),
('h','9. Assumptions, tradeoffs and the challenged requirement'),
('p','Assume the existing owners can provide: (1) a pageable inventory without opening every file; (2) actual applied versions with monotonic revisions; (3) atomic metadata changes and reliable events; (4) remote operation identity/status or enforceable execution lifetime; and (5) content-owned compatibility rules. Confirm allocated concurrency/start rate, regional boundaries, retention, prices and X. Without these contracts, expose unknown/degraded state, retain capacity conservatively, or expand reconciliation; do not claim the fast path remains dependable.'),
('p','<b>Challenge:</b> universal personalized freshness within seconds conflicts with one-minute loads for unknown files. Commit to fast discovery for indexed files and explicit migration coverage. <b>Greatest risk:</b> recovering and maintaining the actual applied version; a wrong baseline corrupts both eligibility and the explanation. <b>Tradeoff:</b> eventually consistent discovery with authoritative decision validation, plus a shared metadata store whose contention must be controlled.'),
('h','10. Narrow Java implementation and validation'),
('p','Implement only the fan-out worker and contracts for candidate paging, durable jobs, shared admission, slow-service calls, conditional results and retry/circuit behavior. Validate duplicate delivery, page replay/crashes, multi-worker capacity, ambiguous timeouts, stale results and tenant fairness. Also verify that healthy baselines remain queryable while slow work is pending. Test doubles must not be presented as distributed production enforcement; document the required atomic adapter operations in the README.'),
('p','Deliberately exclude template application, a new comparator, full event sourcing, a complete UI and infrastructure provisioning. The full expert evaluation corpus and production integration follow the take-home. AI assists drafting and change explanation; it must not own compatibility, acceptance decisions or professional conclusions. The implementation and its README remain separate required deliverables; this document does not assert they are complete.'),
]}
]

pdf_path=OUT/'CASEWARE_ARCHITECTURE_DESIGN.pdf'
c=canvas.Canvas(str(pdf_path),pagesize=A4)
c.setTitle('Template Update Notifications - Architecture Design')
c.setAuthor('Caseware Take-Home Design')
md=['# Template Update Notifications: Architecture Design','', 'Caseware take-home exercise | 16 November 2026', '']
bottoms=[]
for idx,page in enumerate(pages,1):
    c.setFillColor(TEAL); c.setFont('ArialBold',8.2)
    c.drawString(MARGIN,PAGE_H-32,'CASEWARE / SYSTEM DESIGN')
    c.setFillColor(MUTED); c.setFont('Arial',8.2)
    c.drawRightString(PAGE_W-MARGIN,PAGE_H-32,f'{idx:02d} / 04')
    y=PAGE_H-64
    c.setFont('ArialBold',20 if idx==1 else 17); c.setFillColor(INK)
    c.drawString(MARGIN,y,page['title']); y-=19
    c.setFont('Arial',9); c.setFillColor(MUTED); c.drawString(MARGIN,y,page['subtitle']); y-=19
    if idx>1: md.extend(['',f'<!-- PDF page {idx} -->',''])
    for kind,data in page['blocks']:
        if kind in ('p','h','caption'):
            key={'p':'body','h':'heading','caption':'small'}[kind]
            if kind=='h': y-=3
            p=Paragraph(data,styles[key]); _,h=p.wrap(WIDTH,1000)
            p.drawOn(c,MARGIN,y-h); y-=h+({'h':5,'p':6,'caption':8}[kind])
            plain=data.replace('<b>','**').replace('</b>','**')
            md.extend([('## '+plain) if kind=='h' else ('*'+plain+'*' if kind=='caption' else plain),''])
        elif kind=='diagram':
            drawing=architecture() if data=='architecture' else worker_diagram()
            renderPDF.draw(drawing,c,MARGIN,y-drawing.height); y-=drawing.height+3
            renderSVG.drawToFile(drawing,str(OUT/'diagrams'/f'{data}.svg'))
            md.extend(['```mermaid',ARCH_MD if data=='architecture' else WORK_MD,'```',''])
        elif kind=='table':
            cells=[[Paragraph(escape(cell),styles['cell']) for cell in row] for row in data]
            table=Table(cells,colWidths=[WIDTH*.43,WIDTH*.57])
            table.setStyle(TableStyle([
                ('BACKGROUND',(0,0),(-1,0),colors.HexColor('#EAF0F3')),
                ('VALIGN',(0,0),(-1,-1),'TOP'),
                ('LINEBELOW',(0,0),(-1,0),.7,RULE),
                ('LINEBELOW',(0,1),(-1,-1),.35,RULE),
                ('LEFTPADDING',(0,0),(-1,-1),6),('RIGHTPADDING',(0,0),(-1,-1),6),
                ('TOPPADDING',(0,0),(-1,-1),4),('BOTTOMPADDING',(0,0),(-1,-1),4)
            ]))
            _,h=table.wrap(WIDTH,1000); table.drawOn(c,MARGIN,y-h); y-=h+8
            md.append('| '+' | '.join(data[0])+' |'); md.append('| --- | --- |')
            md.extend('| '+' | '.join(row)+' |' for row in data[1:]); md.append('')
        if y<45:
            raise RuntimeError(f'Page {idx} overflows after {kind}: bottom={y:.1f}')
    bottoms.append(round(y,1))
    c.setStrokeColor(RULE); c.setLineWidth(.5); c.line(MARGIN,31,PAGE_W-MARGIN,31)
    c.setFont('Arial',7.5); c.setFillColor(MUTED)
    c.drawString(MARGIN,19,'Architecture design | Implementation is a separate deliverable')
    c.drawRightString(PAGE_W-MARGIN,19,'16 November 2026')
    c.showPage()
c.save()
(ROOT/'DESIGN_DOCUMENT.md').write_text('\n'.join(md),encoding='utf-8')
reader=PdfReader(str(pdf_path))
assert len(reader.pages)==4
for i,p in enumerate(reader.pages,1):
    txt=p.extract_text()
    assert len(txt)>500, f'Page {i} lacks expected content'
print({'pdf':str(pdf_path),'pages':len(reader.pages),'page_bottoms':bottoms,'markdown_words':len(re.findall(r'\S+', '\n'.join(md)))})
