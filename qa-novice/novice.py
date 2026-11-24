"""Exercise the delivered UI against a running server; preserve observations, including UX failures."""
import json
import os
from pathlib import Path
import time
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, TimeoutError as BrowserTimeout

base = os.environ["BASE_URL"].rstrip("/")
user = os.environ.get("QA_USER", "admin")
password = os.environ["QA_PASSWORD"]
out = Path(os.environ.get("QA_OUTPUT", "novice-evidence"))
out.mkdir(parents=True, exist_ok=True)
report = {"base": base, "viewports": []}
ids = ("taskNextAction", "analyzeBtn", "copilotBtn", "cancelAnalysisBtn",
       "includeArchitectureView", "exportDecisionReportDocx", "searchBtn", "businessText",
       "exportVisio", "analysisNextStepsHeading", "analysisReformulationAction", "analysisLensAction",
       "architectureDialogueSend", "architectureDialogueQuestion")
notes = ("taskGuidance", "analysisNextStepText", "statusArea", "analysisScopeActionHint",
         "analysisGuideError", "searchResultsArea", "copilotContent", "analysisRecoveryStatus",
         "aiConnectionWarning", "aiUnavailableWarning", "exportHint", "startupBanner", "architectureDialogueStatus")


def controls(page, selectors):
    return page.evaluate("""(selectors) => Object.fromEntries(selectors.map(id => {
      const e = document.getElementById(id); if (!e) return [id, null];
      const r = e.getBoundingClientRect(), s = getComputedStyle(e);
      return [id, {text:e.innerText?.trim().slice(0,900), title:e.title, disabled:e.disabled,
        ariaDisabled:e.getAttribute('aria-disabled'), ariaDescribedBy:e.getAttribute('aria-describedby'),
        visible:r.width>0 && r.height>0 && s.visibility!=='hidden', top:Math.round(r.top),
        inViewport:r.bottom>0 && r.top<innerHeight && r.width>0 && r.height>0,
        color:s.color, background:s.backgroundColor, opacity:s.opacity, pointerEvents:s.pointerEvents}];
    }))""", list(selectors))


def run(browser, width):
    case = {"width":width, "stages":[], "errors":[], "requests":[], "responses":[], "dialogs":[]}
    report["viewports"].append(case)
    login_user = os.environ.get("QA_USER_MOBILE",user) if width<600 else user
    login_password = os.environ.get("QA_PASSWORD_MOBILE",password) if width<600 else password
    context = browser.new_context(locale="de-DE", viewport={"width":width,"height":844})
    page = context.new_page()
    page.set_default_timeout(9000)
    page.on("pageerror", lambda e: case["errors"].append(str(e)[:400]))
    page.on("request", lambda r: case["requests"].append([r.method,urlparse(r.url).path])
            if r.resource_type in ("xhr","fetch","eventsource") and r.method!="GET" else None)
    page.on("response", lambda r: case["responses"].append([r.status,urlparse(r.url).path])
            if (r.status>=400 or (r.request.method!="GET" and r.request.resource_type in ("xhr","fetch","eventsource"))) and r.request.resource_type in ("xhr","fetch","eventsource","document") else None)
    def dialog(d):
        case["dialogs"].append([d.type,d.message]); d.dismiss()
    page.on("dialog",dialog)
    def shot(name):
        page.screenshot(path=out/f"{width}-{name}.png",animations="disabled",timeout=20000)
    def state(name):
        val={"name":name,"url":page.url,"focus":page.evaluate("document.activeElement.id"),
             "width":page.evaluate("document.documentElement.scrollWidth"),
             "controls":controls(page,ids),"messages":controls(page,notes),
             "alerts":page.locator('[role="alert"]:visible,.alert:visible').all_inner_texts()[:20],
             "currentStep":page.locator('[aria-current="step"]:visible').all_inner_texts()[:4],
             "writes":len(case["requests"])}
        case["stages"].append(val)
        return val
    def click_if_active(selector):
        item=page.locator(selector)
        if item.is_visible() and item.is_enabled() and item.get_attribute("aria-disabled")!="true":
            item.click(); page.wait_for_timeout(500)
            return True
        return False
    try:
        page.goto(base+"/login?lang=de",wait_until="domcontentloaded")
        if page.locator("#username").is_visible():
            page.locator("#username").fill(login_user); page.locator("#password").fill("intentional-wrong-password")
            page.locator("#loginForm button[type=submit]").click()
            page.locator('[role="alert"]').wait_for()
            case["wrongPassword"]=page.locator('[role="alert"]').inner_text()
            page.locator("#username").fill(login_user); page.locator("#password").fill(login_password)
            page.locator("#loginForm button[type=submit]").click()
        page.locator("#businessText").wait_for(state="attached",timeout=30000)
        try:
            page.locator("#onboardingDismiss").wait_for(timeout=10000)
            case["welcome"]=page.locator("#onboardingOverlay").inner_text()
            shot("welcome"); page.locator("#onboardingDismiss").click()
        except BrowserTimeout:
            case["welcome"]=None
        page.locator("#taskNextAction").wait_for(state="attached",timeout=15000)
        page.wait_for_timeout(900)
        state("initial-viewport"); shot("first-view")
        case["initialNextClicked"]=click_if_active("#taskNextAction")
        if case["initialNextClicked"]: state("initial-next")
        # Deliberately take the architecture/export detour before entering a requirement.
        # Return via the user's Next steps link and retain focus and scroll location.
        if click_if_active("#applicationArchitectureLink"):
            state("empty-architecture")
            dialogue=page.locator("#architectureDialogue")
            if dialogue.locator("summary").is_visible():
                if dialogue.get_attribute("open") is None: dialogue.locator("summary").click()
                question=page.locator("#architectureDialogueQuestion")
                for name,value in (("empty",""),("whitespace","   "),("no-source","Welche Prozessschritte sind betroffen?")):
                    question.fill(value)
                    case["question-valid-"+name]=question.evaluate("el=>({valid:el.validity.valid,message:el.validationMessage})")
                    case["question-clicked-"+name]=click_if_active("#architectureDialogueSend")
                    state("dialogue-"+name)
                shot("dialogue-no-source")
                dialogue.locator("summary").click()
            export_tab=page.locator('a[data-subtab="arch-export"]:visible').first
            if export_tab.is_visible():
                export_tab.click(); page.wait_for_timeout(250); state("empty-export");shot("empty-export")
                overview=page.locator('a[data-subtab="arch-overview"]:visible').first
                if overview.is_visible(): overview.click()
            case["architectureNextClicked"]=click_if_active("#architectureNextStepsAction")
            state("architecture-next")
            if not page.locator("#businessText").is_visible():
                click_if_active("#applicationRequirementLink")
        page.locator("#businessText").scroll_into_view_if_needed()
        state("before-input"); shot("empty")
        for target in ("analyzeBtn","copilotBtn"):
            if click_if_active("#"+target):
                state("empty-click-"+target); shot("empty-click-"+target)
        search = page.locator("#searchPanel")
        if search.locator("summary").is_visible():
            if search.get_attribute("open") is None: search.locator("summary").click()
            case["blankSearchClicked"]=click_if_active("#searchBtn")
            state("empty-search")
            page.locator("#searchInput").fill("QAxyznomatch174815")
            click_if_active("#searchBtn")
            try: page.locator("#searchResultsArea").wait_for(timeout=6000)
            except BrowserTimeout: pass
            page.wait_for_timeout(450); state("search-no-match")
        page.locator("#businessText").fill("Eine Stadtverwaltung möchte den Eingang und die Freigabe von Rechnungen nachvollziehbar dokumentieren.")
        page.wait_for_timeout(550); state("entered")
        scope=page.locator("details").filter(has=page.locator("#analysisTaxonomyRoots")).last
        if scope.get_attribute("open") is None: scope.locator(":scope > summary").click()
        roots=page.locator("#analysisTaxonomyRoots input")
        roots.first.wait_for(timeout=120000)
        for root in roots.all(): root.uncheck()
        page.locator("#analysisMode").select_option("TAXONOMIES_ONLY")
        case["emptyScopeClicked"]=click_if_active("#analyzeBtn")
        state("no-taxonomy"); shot("no-taxonomy")
        target=page.locator('#analysisTaxonomyRoots input[data-taxonomy-root="BP"]')
        if not target.count(): target=roots.first
        target.check()
        state("single-root-selected")
        if width>=1000 and os.environ.get("QA_LIVE_CLICK","1")=="1":
            page.locator("#analyzeBtn").scroll_into_view_if_needed()
            start=page.locator("#analyzeBtn")
            if start.is_enabled() and start.get_attribute("aria-disabled")!="true":
                # Same screen position, like a user's real double-click. Native disabled
                # controls ignore the second physical click. The network log shows admission.
                page.evaluate("""() => {window.noviceStartClicks=0;
                  document.addEventListener('click',e=>{if(e.target.closest?.('#analyzeBtn'))
                    window.noviceStartClicks++},true);}""")
                start.dblclick(delay=70)
                case["started"]=True
                case["deliveredStartClicks"]=page.evaluate("window.noviceStartClicks")
            else:
                case["started"]=False
            state("started")
            completed={"recovery":None,"scoreCount":0,"startAction":None,"error":""}
            deadline=time.monotonic()+int(os.environ.get("QA_LIVE_SECONDS","90"))
            while time.monotonic()<deadline:
                page.wait_for_timeout(2000)
                completed=page.evaluate("""() => {const current=window.TaxonomyState;
                  return {recovery:current?.analysisRecovery?.state || null,
                  scoreCount:Object.keys(current?.currentScores || {}).length,
                  positiveCount:Object.values(current?.currentScores || {}).filter(s=>Number(s)>0).length,
                  zeroCount:Object.values(current?.currentScores || {}).filter(s=>s===0).length,
                  analysisStatus:current?.lastAnalysisStatus,
                  openQuestions:current?.analysisRecovery?.openQuestions?.length,
                  startAction:document.querySelector('#taskNextAction')?.dataset.action,
                  error:document.querySelector('#statusArea .alert-danger')?.innerText || ''};}""")
                if completed["recovery"] in ("COMPLETED","COMPLETED_WITH_GAPS","FAILED","CANCELLED","STOPPED","PAUSED") or completed.get("analysisStatus") in ("SUCCEEDED","COMPLETED","PARTIAL","ERROR","CANCELLED") or completed["error"]:
                    break
            case["lastLiveObservation"]=completed
            state("live-observation"); shot("live-observation")
            if completed.get("startAction")=="review-results" and click_if_active("#taskNextAction"):
                state("review-results");shot("review-results")
                if click_if_active("#taskNextAction"):
                    dialogue=page.locator("#architectureDialogue")
                    if dialogue.locator("summary").is_visible():
                        if dialogue.get_attribute("open") is None: dialogue.locator("summary").click()
                        page.locator("#architectureDialogueQuestion").fill("   ")
                        case["question-clicked-after-review"]=click_if_active("#architectureDialogueSend")
                        state("dialogue-whitespace-after-review");shot("dialogue-after-review")
            elif click_if_active("#cancelAnalysisBtn"):
                page.wait_for_timeout(1000);state("after-cancel");shot("after-cancel")
    except Exception as e:
        case["executionError"]=str(e)[:2500]
        try:
            if not page.locator("#loginForm").is_visible(): state("interrupted");shot("interrupted")
        except Exception: pass
    finally:
        context.close()
        (out/"observations.json").write_text(json.dumps(report,indent=2,ensure_ascii=False))

with sync_playwright() as pw:
    options={"headless":True,"args":["--disable-dev-shm-usage"]}
    if os.environ.get("BROWSER_PATH"): options["executable_path"]=os.environ["BROWSER_PATH"]
    with pw.chromium.launch(**options) as browser:
        for view in os.environ.get("QA_WIDTHS","1280,390").split(","): run(browser,int(view))
print(json.dumps([{k:v for k,v in c.items() if k in ("width","wrongPassword","executionError","lastLiveObservation","started")} for c in report["viewports"]],ensure_ascii=False))
if any("executionError" in c for c in report["viewports"]): raise SystemExit(1)
