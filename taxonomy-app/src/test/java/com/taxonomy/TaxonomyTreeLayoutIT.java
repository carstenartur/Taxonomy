package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.testsupport.BrowserSession;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real D3, text metrics, SVG geometry and Canvas drawing; no application context or LLM. */
@Tag("browser")
@Tag("ui-acceptance")
class TaxonomyTreeLayoutIT {
    private static HttpServer server;
    private static BrowserSession session;
    private static RemoteWebDriver driver;
    private static WebDriverWait wait;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.equals("/fixture")) {
                body = """
                        <!doctype html><html lang="de"><meta charset="utf-8">
                        <style>body{margin:16px}#tree{width:900px;overflow:auto}</style>
                        <div id="tree"></div><script>window.TaxonomyI18n={t:key=>key};</script>
                        <script src="/d3.js"></script><script src="/views.js"></script></html>
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            } else {
                String resource = switch (path) {
                    case "/d3.js" -> "/META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js";
                    case "/views.js" -> "/static/js/core/taxonomy-views.js";
                    default -> null;
                };
                if (resource == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                try (var input = TaxonomyTreeLayoutIT.class.getResourceAsStream(resource)) {
                    if (input == null) throw new IllegalStateException("Missing browser resource " + resource);
                    body = input.readAllBytes();
                }
                exchange.getResponseHeaders().set("Content-Type", "application/javascript");
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        session = BrowserSession.open(server.getAddress().getPort(), Path.of("target/tree-layout-downloads"));
        driver = session.driver();
        wait = new WebDriverWait(driver, Duration.ofSeconds(10));
    }

    @AfterAll static void stop() {
        try { if (session != null) session.close(); }
        finally { if (server != null) server.stop(0); }
    }

    @BeforeEach void fixture() {
        driver.get(session.origin() + "/fixture");
        driver.executeScript("""
                const proto=CanvasRenderingContext2D.prototype;
                window.originalCanvasPaintMethods={clearRect:proto.clearRect,
                    fillText:proto.fillText,strokeText:proto.strokeText};
                """);
    }

    @AfterEach void restoreCanvasPaintMethods() {
        // Prototype instrumentation belongs to one measurement, not the browser session.
        driver.executeScript("""
                if(window.originalCanvasPaintMethods)
                    Object.assign(CanvasRenderingContext2D.prototype,window.originalCanvasPaintMethods);
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"renderTreeDiagram", "renderTreeCanvas"})
    void expandAllOpensTheCompleteSelectedTreeAndMakesItsSavedReasonAvailable(String renderer) {
        driver.executeScript("""
                window.data=[{code:'fixture-1',children:[{code:'fixture-2',children:[
                  {code:'fixture-3',children:[{code:'fixture-4',children:[{code:'fixture-5'}]}]}]}]}];
                window.TaxonomyState={currentReasons:{'fixture-5':'Complete saved reason. '.repeat(100)+'FINAL TREE REASON'}};
                window.original=JSON.stringify(data);
                TaxonomyViews[arguments[0]](document.getElementById('tree'),data,{'fixture-5':0.75});
                """, renderer);
        if (renderer.equals("renderTreeDiagram")) settled();
        driver.findElement(By.cssSelector(".tax-diagram-expand-all")).click();
        if (renderer.equals("renderTreeDiagram")) {
            settled();
            assertThat(driver.findElements(By.cssSelector("[data-diagram-node]"))).hasSize(5);
            driver.findElement(By.cssSelector("[data-diagram-node='fixture-1']")).sendKeys(Keys.END);
        } else {
            var canvas = driver.findElement(By.cssSelector("canvas"));
            canvas.sendKeys(Keys.END);
            assertThat(canvas.getAttribute("data-diagram-node")).isEqualTo("fixture-5");
        }
        driver.findElement(By.cssSelector(".tax-diagram-rationale")).click();
        assertThat(driver.findElement(By.id("decisionReasonDialog")).getText()).contains("FINAL TREE REASON", "0.75%");
        assertThat(driver.executeScript("return JSON.stringify(data)===original")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"renderTreeDiagram", "renderTreeCanvas"})
    void openingAnInitiallyCollapsedBranchRevealsOneLevel(String renderer) {
        driver.executeScript("""
                window.data=[{code:'fixture-1',children:[{code:'fixture-2',children:[
                  {code:'fixture-3',children:[{code:'fixture-4',children:[{code:'fixture-5'}]}]}]}]}];
                window.original=JSON.stringify(data);
                TaxonomyViews[arguments[0]](document.getElementById('tree'),data,{});
                """, renderer);
        if (renderer.equals("renderTreeCanvas")) {
            var canvas = driver.findElement(By.cssSelector("canvas"));
            canvas.sendKeys(Keys.HOME, Keys.END);
            assertThat(canvas.getAttribute("data-diagram-node")).isEqualTo("fixture-3");
            canvas.sendKeys(Keys.ENTER, Keys.END);
            assertThat(canvas.getAttribute("data-diagram-node")).isEqualTo("fixture-4");
            assertThat(canvas.getAttribute("aria-expanded")).isEqualTo("false");
            canvas.sendKeys(Keys.ENTER, Keys.END);
            assertThat(canvas.getAttribute("data-diagram-node")).isEqualTo("fixture-5");
        } else {
            settled();
            assertThat(driver.findElement(By.cssSelector("[data-diagram-node='fixture-3'] .tv-toggle")).getText()).isEqualTo("+");
            driver.findElement(By.cssSelector("[data-diagram-node='fixture-3']")).click();
            settled();
            assertThat(driver.findElement(By.cssSelector("[data-diagram-node='fixture-3'] .tv-toggle")).getText()).isEmpty();
            assertThat(driver.findElements(By.cssSelector("[data-diagram-node='fixture-5']"))).isEmpty();
            var next = driver.findElement(By.cssSelector("[data-diagram-node='fixture-4']"));
            assertThat(next.getAttribute("aria-expanded")).isEqualTo("false");
            next.click(); settled();
            assertThat(driver.findElements(By.cssSelector("[data-diagram-node='fixture-5']"))).hasSize(1);
        }
        assertThat(driver.executeScript("return JSON.stringify(data)===original")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"renderTreeDiagram", "renderTreeCanvas", "export"})
    void longLabelsAndScoreBadgesFitWithoutOverlappingOtherNodes(String renderer) {
        driver.executeScript("""
                window.data=[{code:'fixture-root',name:'Business processes and their full original catalogue descriptions '.repeat(3),children:[
                  {code:'fixture-legal',name:'Provide legal advice to headquarters and support international agreements '.repeat(2)},
                  {code:'fixture-personnel',name:'Personnel support and human resource management '.repeat(3),children:[
                    {code:'fixture-hr',name:'Human resource management for the complete organisation '.repeat(3)},
                    {code:'fixture-manpower',name:'Manpower and workforce development '.repeat(3)}]}]}];
                window.original=JSON.stringify(data);
                var scores={'fixture-root':80,'fixture-legal':40,'fixture-personnel':40,'fixture-hr':30,'fixture-manpower':10};
                window.painted=[];
                var proto=CanvasRenderingContext2D.prototype, draw=proto.fillText, clear=proto.clearRect;
                proto.clearRect=function(...args){window.painted=[];return clear.apply(this,args);};
                proto.fillText=function(text,x,y,...args){
                  var m=this.measureText(text), p=this.getTransform().transformPoint({x,y});
                  var left=p.x/devicePixelRatio-(this.textAlign==='right'?m.width:this.textAlign==='center'?m.width/2:0);
                  window.painted.push({text,left,right:left+m.width,top:p.y/devicePixelRatio-8,bottom:p.y/devicePixelRatio+8});
                  return draw.call(this,text,x,y,...args);
                };
                if(arguments[0]==='export') document.getElementById('tree').append(TaxonomyViews.buildExportSVG(data,scores,{expandAll:true}));
                else TaxonomyViews[arguments[0]](document.getElementById('tree'),data,scores);
                """, renderer);
        if (renderer.equals("renderTreeDiagram")) settled();
        Object violations = driver.executeScript("""
                var svg=document.querySelector('svg'), canvas=document.querySelector('canvas'), errors=[];
                var width=svg?Number(svg.getAttribute('width')):canvas.width/devicePixelRatio;
                var height=svg?Number(svg.getAttribute('height')):canvas.height/devicePixelRatio;
                var boxes=svg?[...svg.querySelectorAll('g')].filter(g=>g.querySelector(':scope > circle')&&
                    (!g.__data__||g.__data__.data.code!=='__root__')).map(g=>{
                      var b=g.getBoundingClientRect(), s=svg.getBoundingClientRect();
                      return {text:g.textContent,left:b.left-s.left,right:b.right-s.left,top:b.top-s.top,bottom:b.bottom-s.top};
                    }):painted.filter(b=>b.text!=='+');
                for(var b of boxes) if(b.left<8||b.right>width-8||b.top<8||b.bottom>height-8) errors.push('clipped '+b.text);
                for(var i=0;i<boxes.length;i++) for(var j=i+1;j<boxes.length;j++){
                  var a=boxes[i],b=boxes[j];
                  if(a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top) errors.push('overlap '+a.text+' / '+b.text);
                }
                if(boxes.length<5) errors.push('missing visible nodes');
                if(JSON.stringify(data)!==original) errors.push('source hierarchy mutated');
                return errors;
                """);
        assertThat(violations).as(renderer + " occupied label bounds").isEqualTo(java.util.List.of());
    }

    @Test void paintMeasurementsRemainIsolatedWithoutNavigatingBetweenRenderers() {
        // Exercise the same cleanup JUnit invokes, even when the document is retained.
        for (String renderer : new String[]{"renderTreeDiagram", "renderTreeCanvas", "renderTreeCanvas"}) {
            longLabelsAndScoreBadgesFitWithoutOverlappingOtherNodes(renderer);
            if (renderer.equals("renderTreeCanvas"))
                assertThat(driver.executeScript("return painted.length")).isEqualTo(10L);
            restoreCanvasPaintMethods();
        }
        collapsedMarkersHaveADarkGlyphAndLightHaloEvenAtOnePercent("renderTreeCanvas");
        restoreCanvasPaintMethods();
        assertThat(driver.executeScript("""
                return Object.entries(originalCanvasPaintMethods).every(
                    ([name,method])=>CanvasRenderingContext2D.prototype[name]===method);
                """)).as("Canvas measurement hooks must not leak into later tests").isEqualTo(true);
    }

    @Test void defaultExportKeepsDeeperBranchesCollapsed() {
        Object exported = driver.executeScript("""
                var data=[{code:'fixture-1',children:[{code:'fixture-2',children:[
                  {code:'fixture-3',children:[{code:'fixture-4',children:[{code:'fixture-5'}]}]}]}]}];
                return TaxonomyViews.buildExportSVG(data,{},{}).textContent;
                """);
        assertThat(exported.toString()).contains("fixture-3").doesNotContain("fixture-4", "fixture-5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"renderTreeDiagram", "renderTreeCanvas"})
    void fullTreePrintUsesTheFilteredSelectionAndPreservesTheLiveDiagram(String renderer) throws Exception {
        driver.executeScript("""
                window.data=[{code:'fixture-1',children:[{code:'fixture-2',children:[
                  {code:'fixture-3',name:'Printable branch label '.repeat(12),children:[{code:'fixture-4',children:[{code:'fixture-5'}]}]}]},
                  {code:'fixture-zero'}]}];
                window.scores={'fixture-1':0,'fixture-2':0,'fixture-3':0,'fixture-4':0,'fixture-5':0.75,'fixture-zero':0};
                window.TaxonomyState={currentReasons:{'fixture-5':'Complete saved rationale. '.repeat(200)+'FINAL TREE EXPLANATION',
                    'fixture-zero':'EXCLUDED ZERO REASON'}};
                window.savedBefore=JSON.stringify({data,scores,reasons:TaxonomyState.currentReasons});
                TaxonomyViews[arguments[0]](document.getElementById('tree'),TaxonomyViews.filterZeroRelevance(data,scores,{}),scores);
                """, renderer);
        if (renderer.equals("renderTreeDiagram")) settled();
        driver.executeScript("""
                document.querySelector('.tax-diagram-controls button').click();
                document.querySelector('.tax-diagram-print-button').focus();
                window.screenBefore=document.querySelector('#tree').innerHTML;
                window.canvasBefore=document.querySelector('canvas')?.toDataURL();
                window.printFrame=document.createElement('iframe');document.body.append(printFrame);window.printCalls=0;
                window.open=()=>({document:printFrame.contentDocument,closed:false,focus(){},addEventListener(){},requestAnimationFrame(fn){fn()},print(){printCalls++}});
                """);
        for (String mode : new String[]{"pages", "single"}) {
            new org.openqa.selenium.support.ui.Select(driver.findElement(By.cssSelector(".tax-diagram-print-mode"))).selectByValue(mode);
            // The paper control changes availability with the selected layout; capture
            // the live diagram after that deliberate user preference change.
            driver.executeScript("window.screenBefore=document.querySelector('#tree').innerHTML");
            driver.findElement(By.cssSelector(".tax-diagram-print-button")).click();
            assertThat(driver.executeScript("""
                    const doc=printFrame.contentDocument;
                    return ['fixture-1','fixture-2','fixture-3','fixture-4','fixture-5','0.75%','FINAL TREE EXPLANATION'].every(text=>doc.body.textContent.includes(text))
                        && !doc.body.textContent.includes('fixture-zero') && !doc.body.textContent.includes('EXCLUDED ZERO REASON')
                        && doc.querySelectorAll('svg').length>0 && doc.querySelectorAll('canvas').length===0
                        && document.querySelector('#tree').innerHTML===screenBefore
                        && document.querySelector('canvas')?.toDataURL()===canvasBefore
                        && JSON.stringify({data,scores,reasons:TaxonomyState.currentReasons})===savedBefore;
                    """)).as(renderer+" complete filtered vector print in "+mode).isEqualTo(true);
            int plannedPages=((Number)driver.executeScript("return printFrame.contentDocument.querySelectorAll('.diagram-print-page').length")).intValue();
            if (mode.equals("single")) assertThat(plannedPages).isEqualTo(1);
            else {
                assertThat(plannedPages).isGreaterThan(1);
                assertThat(driver.executeScript("""
                        const doc=printFrame.contentDocument;
                        const diagrams=[...doc.querySelectorAll('.tree-print-page > svg')];
                        const intact=diagrams.every(svg=>{const vb=svg.viewBox.baseVal;
                            return vb.width<=1040 && vb.height<=620 && [...svg.querySelectorAll('[data-print-fragment]')].every(node=>{
                                const b=node.getBBox();return b.x>=0 && b.y>=0 && b.x+b.width<=vb.width && b.y+b.height<=vb.height;});});
                        return intact && ['fixture-1','fixture-2','fixture-3','fixture-4','fixture-5'].every(code=>
                            doc.querySelectorAll('[data-print-node="'+code+'"]').length===1)
                            && doc.querySelectorAll('.diagram-evidence-page').length>0;
                        """)).as(renderer+" A4 pages retain complete labels, scores, and linked full evidence").isEqualTo(true);
            }
            String html=(String)driver.executeScript("return printFrame.contentDocument.documentElement.outerHTML");
            String source=driver.getWindowHandle();driver.switchTo().newWindow(WindowType.TAB);
            try {
                driver.get("data:text/html;charset=UTF-8;base64,"+Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
                var result=((HasCdp)new Augmenter().augment(driver)).executeCdpCommand("Page.printToPDF",Map.of("preferCSSPageSize",true,"printBackground",true));
                byte[] bytes=Base64.getDecoder().decode((String)result.get("data"));
                Path directory=Path.of("target/decision-diagram-evidence");Files.createDirectories(directory);
                String name=(renderer.equals("renderTreeCanvas")?"tree-canvas-":"tree-svg-")+mode+"-print";
                Files.write(directory.resolve(name+".pdf"),bytes);
                try(var pdf=Loader.loadPDF(bytes)) {
                    var stripper=new PDFTextStripper();
                    String pdfText=stripper.getText(pdf);
                    assertThat(pdfText).as(renderer+" PDF filter in "+mode).doesNotContain("fixture-zero", "EXCLUDED ZERO REASON");
                    assertThat(pdfText).contains("0.75%", "FINAL TREE EXPLANATION");
                    assertThat(pdf.getNumberOfPages()).as(renderer+" PDF has exactly the planned pages in "+mode).isEqualTo(plannedPages);
                    if(mode.equals("single")) {
                        assertThat(pdfText).as(renderer+" complete single-sheet PDF content")
                            .contains("fixture-1","fixture-2","fixture-3","fixture-4","fixture-5","0.75%");
                        assertThat(pdf.getNumberOfPages()).isEqualTo(1);
                        var box=pdf.getPage(0).getMediaBox();
                        assertThat(Math.max(box.getWidth(),box.getHeight())).isGreaterThan(842f);
                    } else {
                        assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
                        for(int page=1;page<=pdf.getNumberOfPages();page++) {
                            var box=pdf.getPage(page-1).getMediaBox();
                            assertThat(box.getWidth()).isBetween(840f,844f);
                            assertThat(box.getHeight()).isBetween(593f,597f);
                            stripper.setStartPage(page);stripper.setEndPage(page);
                            assertThat(stripper.getText(pdf)).as(renderer+" populated A4 page "+page).isNotBlank();
                        }
                    }
                }
            } finally {driver.close();driver.switchTo().window(source);}
        }
        assertThat(driver.executeScript("return document.querySelector('#tree').innerHTML===screenBefore && document.querySelector('canvas')?.toDataURL()===canvasBefore && JSON.stringify({data,scores,reasons:TaxonomyState.currentReasons})===savedBefore")).isEqualTo(true);
        assertThat(driver.executeScript("return printCalls")).isEqualTo(2L);
        driver.executeScript("window.open=()=>null");
        driver.findElement(By.cssSelector(".tax-diagram-print-button")).click();
        assertThat(driver.findElement(By.cssSelector(".decision-print-status")).getText()).contains("views.diagram.printBlocked");
    }

    @ParameterizedTest
    @CsvSource({
        "renderTreeDiagram,A1,single", "renderTreeDiagram,A0,single",
        "renderTreeCanvas,A1,single", "renderTreeCanvas,A0,single",
        "renderDecisionMap,A1,single", "renderDecisionMap,A0,single",
        "renderTreeDiagram,A1,taxonomy", "renderTreeDiagram,A0,taxonomy",
        "renderTreeCanvas,A1,taxonomy", "renderTreeCanvas,A0,taxonomy",
        "renderDecisionMap,A1,taxonomy", "renderDecisionMap,A0,taxonomy"
    })
    void fixedPaperPrintKeepsConnectedPositiveSubtaxonomiesOnTheRequestedSheets(String renderer, String paper, String mode) throws Exception {
        driver.executeScript("""
                window.TaxonomyUtils={isTaxonomyContainer:n=>!!n.taxonomyContainer,
                    contentRoots:items=>items.flatMap(n=>n.taxonomyContainer?n.children:[n])};
                window.data=['BP','BR'].map((area,index)=>({code:area,name:'Area '+area,taxonomyContainer:true,children:[
                    {code:'fixture-context-'+index,name:'Context ancestor',children:[
                        {code:'fixture-mid-'+index,name:'Intermediate context',children:[
                            {code:'fixture-positive-'+index,name:'Selected positive leaf'}]},
                        {code:'fixture-zero-'+index,name:'Excluded zero'},
                        {code:'fixture-missing-'+index,name:'Excluded unknown'},
                        {code:'fixture-pending-'+index,name:'Excluded pending product'}]}]}));
                window.scores={};window.details={};window.reasons={};
                for(let i=0;i<2;i++){
                    scores['fixture-context-'+i]=0;scores['fixture-mid-'+i]=0;
                    scores['fixture-positive-'+i]=0.75;scores['fixture-zero-'+i]=0;scores['fixture-pending-'+i]=75;
                    details['fixture-pending-'+i]={kind:'PRODUCT_SUITABILITY'};
                    reasons['fixture-positive-'+i]='SELECTED REASON '+i+' complete final sentence.';
                    reasons['fixture-zero-'+i]='EXCLUDED ZERO REASON';
                    reasons['fixture-missing-'+i]='EXCLUDED UNKNOWN REASON';
                    reasons['fixture-pending-'+i]='EXCLUDED PENDING REASON';
                }
                window.TaxonomyState={currentReasons:reasons,currentScoreDetails:details};
                window.savedBefore=JSON.stringify({data,scores,reasons,details});
                window.selected=TaxonomyViews.filterRelevance(data,scores,details,'positive');
                TaxonomyViews[arguments[0]](document.getElementById('tree'),selected,scores);
                """, renderer);
        if (renderer.equals("renderTreeDiagram")) settled();
        if (renderer.equals("renderDecisionMap")) {
            wait.until(d -> Boolean.TRUE.equals(driver.executeScript("return document.getElementById('tree').dataset.transitionsComplete==='true'")));
        }
        new org.openqa.selenium.support.ui.Select(driver.findElement(By.cssSelector(".tax-diagram-print-mode"))).selectByValue(mode);
        new org.openqa.selenium.support.ui.Select(driver.findElement(By.cssSelector(".tax-diagram-print-paper"))).selectByValue(paper);
        driver.executeScript("""
                window.screenBefore=document.getElementById('tree').innerHTML;
                window.canvasBefore=document.querySelector('canvas')?.toDataURL();
                window.printFrame=document.createElement('iframe');document.body.append(printFrame);
                window.open=()=>({document:printFrame.contentDocument,closed:false,focus(){},addEventListener(){},requestAnimationFrame(fn){fn()},print(){}});
                """);
        driver.findElement(By.cssSelector(".tax-diagram-print-button")).click();
        int expectedPages = mode.equals("taxonomy") ? 2 : 1;
        assertThat(driver.executeScript("return printFrame.contentDocument.querySelectorAll('.diagram-print-page').length")).isEqualTo((long) expectedPages);
        assertThat(driver.executeScript("""
                const doc=printFrame.contentDocument,errors=[];
                for(let i=0;i<2;i++)for(const kind of ['context','mid','positive']){
                    const code='fixture-'+kind+'-'+i;
                    if(doc.querySelectorAll('[data-print-node="'+code+'"]').length!==1)errors.push('missing or repeated '+code);
                }
                if(doc.querySelectorAll('path').length!==4)errors.push('missing hierarchy edges');
                if(doc.querySelector('[data-parent-page]'))errors.push('hierarchy fragmented into continuation stubs');
                if(doc.querySelector('[data-print-section]'))errors.push('sheet tiled from A4 fragments');
                for(const reference of doc.querySelectorAll('a[href^="#diagram-evidence-"]')){
                    const target=doc.querySelector(reference.getAttribute('href'));
                    if(!target||target.dataset.evidenceCode!==reference.closest('[data-print-node]').dataset.printNode)
                        errors.push('explanation reference points outside its node');
                }
                for(const page of doc.querySelectorAll('.diagram-print-page')){
                    const svg=page.querySelector(':scope > svg'),bounds=svg.getBoundingClientRect();
                    for(const node of svg.querySelectorAll('[data-print-node]')){
                        const box=node.getBoundingClientRect();
                        if(box.left<bounds.left-0.5||box.top<bounds.top-0.5||box.right>bounds.right+0.5||box.bottom>bounds.bottom+0.5)
                            errors.push('clipped '+node.dataset.printNode);
                    }
                }
                if(doc.body.textContent.includes('EXCLUDED')||doc.body.textContent.includes('fixture-zero')
                    ||doc.body.textContent.includes('fixture-missing')||doc.body.textContent.includes('fixture-pending'))errors.push('filter escaped');
                if(document.getElementById('tree').innerHTML!==screenBefore||document.querySelector('canvas')?.toDataURL()!==canvasBefore
                    ||JSON.stringify({data,scores,reasons,details})!==savedBefore)errors.push('live state changed');
                return errors;
                """)).isEqualTo(java.util.List.of());
        String html = (String) driver.executeScript("return printFrame.contentDocument.documentElement.outerHTML");
        String source = driver.getWindowHandle(); driver.switchTo().newWindow(WindowType.TAB);
        try {
            driver.get("data:text/html;charset=UTF-8;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
            var result = ((HasCdp) new Augmenter().augment(driver)).executeCdpCommand("Page.printToPDF", Map.of("preferCSSPageSize", true, "printBackground", true));
            byte[] bytes = Base64.getDecoder().decode((String) result.get("data"));
            Path directory = Path.of("target/decision-diagram-evidence"); Files.createDirectories(directory);
            Files.write(directory.resolve(renderer + "-" + paper + "-" + mode + ".pdf"), bytes);
            try (var pdf = Loader.loadPDF(bytes)) {
                assertThat(pdf.getNumberOfPages()).as(renderer + " " + paper + " " + mode + " physical PDF sheets").isEqualTo(expectedPages);
                float shortSide = (paper.equals("A0") ? 841 : 594) * 72f / 25.4f;
                float longSide = (paper.equals("A0") ? 1189 : 841) * 72f / 25.4f;
                for (int index = 0; index < expectedPages; index++) {
                    var box = pdf.getPage(index).getMediaBox();
                    assertThat(Math.min(box.getWidth(), box.getHeight())).isCloseTo(shortSide, org.assertj.core.data.Offset.offset(1f));
                    assertThat(Math.max(box.getWidth(), box.getHeight())).isCloseTo(longSide, org.assertj.core.data.Offset.offset(1f));
                    var stripper = new PDFTextStripper(); stripper.setStartPage(index + 1); stripper.setEndPage(index + 1);
                    String text = stripper.getText(pdf);
                    assertThat(text).contains("0.75%", "complete final sentence.").doesNotContain("EXCLUDED", "fixture-zero", "fixture-missing", "fixture-pending");
                    if (mode.equals("taxonomy")) {
                        assertThat(text).contains("fixture-positive-" + index, "SELECTED REASON " + index)
                            .doesNotContain("fixture-positive-" + (1 - index), "SELECTED REASON " + (1 - index));
                    } else {
                        assertThat(text).contains("fixture-positive-0", "fixture-positive-1", "SELECTED REASON 0", "SELECTED REASON 1");
                    }
                }
            }
        } finally { driver.close(); driver.switchTo().window(source); }
        driver.executeScript("TaxonomyViews[arguments[0]](document.getElementById('tree'),selected,scores)", renderer);
        assertThat(driver.findElement(By.cssSelector(".tax-diagram-print-mode")).getDomProperty("value")).isEqualTo(mode);
        assertThat(driver.findElement(By.cssSelector(".tax-diagram-print-paper")).getDomProperty("value")).isEqualTo(paper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"renderTreeDiagram", "renderTreeCanvas", "export"})
    void collapsedMarkersHaveADarkGlyphAndLightHaloEvenAtOnePercent(String renderer) {
        driver.executeScript("""
                var data=[{code:'one',children:[{code:'two',children:[
                  {code:'three',children:[{code:'four'}]}]}]}];
                window.markerPaint=[];
                var proto=CanvasRenderingContext2D.prototype;
                const clear=proto.clearRect;
                proto.clearRect=function(...args){markerPaint=[];return clear.apply(this,args);};
                for(var method of ['strokeText','fillText']) {
                  const original=proto[method], operation=method;
                  proto[method]=function(text,...args) {
                    if(text==='+') markerPaint.push({operation,fill:this.fillStyle,stroke:this.strokeStyle,width:this.lineWidth});
                    return original.call(this,text,...args);
                  };
                }
                if(arguments[0]==='export') document.getElementById('tree').append(TaxonomyViews.buildExportSVG(data,{three:1},{}));
                else TaxonomyViews[arguments[0]](document.getElementById('tree'),data,{three:1});
                """, renderer);
        if (renderer.equals("renderTreeDiagram")) settled();
        Object readable = driver.executeScript("""
                if(arguments[0]==='renderTreeCanvas') {
                  return markerPaint.length===2 && markerPaint[0].operation==='strokeText'
                    && markerPaint[1].operation==='fillText' && markerPaint[0].stroke==='#ffffff'
                    && markerPaint[0].width===2 && markerPaint[1].fill==='#222222';
                }
                var marker=[...document.querySelectorAll('svg text')].find(t=>t.textContent==='+');
                if(!marker) return false;
                var style=getComputedStyle(marker);
                return style.fill==='rgb(34, 34, 34)' && style.stroke==='rgb(255, 255, 255)'
                  && style.strokeWidth==='2px' && style.paintOrder.split(' ')[0]==='stroke';
                """, renderer);
        assertThat(readable).as(renderer + " collapsed marker must retain both contrasting paints").isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"renderTreeDiagram", "export"})
    void curvesAvoidLongLabelsBesideAShortParent(String renderer) {
        driver.executeScript("""
                var data=[{code:'A',children:Array.from({length:5},(_,i)=>({code:'A-'+i}))},
                  {code:'B',name:'A long original label next to a short parent '.repeat(12)}];
                if(arguments[0]==='export') document.getElementById('tree').append(TaxonomyViews.buildExportSVG(data,{},{}));
                else TaxonomyViews.renderTreeDiagram(document.getElementById('tree'),data,{});
                """, renderer);
        if (renderer.equals("renderTreeDiagram")) settled();
        Object crossed = driver.executeScript("""
                var labels=[...document.querySelectorAll('svg text')].filter(t=>t.textContent).map(t=>t.getBoundingClientRect());
                return [...document.querySelectorAll('path.tv-link')].some(path=>{
                  var length=path.getTotalLength(), matrix=path.getScreenCTM();
                  for(var i=0;i<=200;i++){
                    var p=path.getPointAtLength(length*i/200), point=new DOMPoint(p.x,p.y).matrixTransform(matrix);
                    if(labels.some(b=>point.x>b.left&&point.x<b.right&&point.y>b.top&&point.y<b.bottom)) return true;
                  }
                  return false;
                });
                """);
        assertThat(crossed).as("Connectors must stay outside every node label").isEqualTo(false);
    }

    private static void settled() {
        wait.until(ignored -> "true".equals(driver.findElement(By.id("tree")).getAttribute("data-transitions-complete")));
    }
}
