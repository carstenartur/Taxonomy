package com.taxonomy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.*;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.remote.Augmenter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** Offline authored presentation fixtures, exercised through the existing Maven browser owner. */
@Tag("ui-acceptance") @Tag("browser")
class TaxonomyDecisionDiagramUiIT {
    static HttpServer server;
    static BrowserSession session;
    static RemoteWebDriver browser;
    @BeforeAll static void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",0),0);
        server.createContext("/", TaxonomyDecisionDiagramUiIT::serve); server.start();
        session=BrowserSession.open(server.getAddress().getPort(),Path.of("target/decision-diagram-evidence/downloads"));
        browser=session.driver();
    }
    @AfterAll static void close() throws Exception { try {if(session!=null)session.close();} finally {if(server!=null)server.stop(0);} }
    @BeforeEach void open() {
        browser.manage().window().setSize(new Dimension(1440,1000));
        browser.get(session.origin()+"/fixture");
        new WebDriverWait(browser,Duration.ofSeconds(10)).until(d->Boolean.TRUE.equals(browser.executeScript("return document.querySelector('#diagram').dataset.transitionsComplete==='true'")));
    }
    @Test void measuredNodeAndAreaBoundsStaySeparateAfterBranchChanges() throws Exception {
        assertThat(browser.executeScript(GEOMETRY)).isEqualTo("");
        capture("decision-tree.png");
        browser.findElement(By.cssSelector("[data-diagram-node='fixture-parent']")).click(); settle();
        assertThat(browser.executeScript(GEOMETRY)).isEqualTo("");
        browser.findElement(By.cssSelector("[data-diagram-node='fixture-parent']")).sendKeys(Keys.ENTER); settle();
        assertThat(browser.executeScript(GEOMETRY)).isEqualTo("");
    }
    @Test void explicitSavedReasonWorksWithKeyboardAndTouchAndReturnsFocus() throws Exception {
        var action=browser.findElement(By.cssSelector("[data-diagram-node='fixture-parent'] .dm-reason-action"));
        action.sendKeys(Keys.ENTER);
        assertThat(browser.findElement(By.id("decisionReasonDialog")).getText()).contains("CATALOGUE ONLY", "SAVED EXPLANATION", "Final saved line.");
        capture("decision-rationale.png");
        browser.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        assertThat(browser.findElements(By.id("decisionReasonDialog"))).isEmpty();
        assertThat(browser.executeScript("return document.activeElement.classList.contains('dm-reason-action')")).isEqualTo(true);
        browser.executeScript("document.querySelector(\"[data-diagram-node='fixture-parent'] .dm-reason-action\").dispatchEvent(new PointerEvent('click',{bubbles:true,pointerType:'touch'}))");
        assertThat(browser.findElement(By.id("decisionReasonDialog")).getText()).contains("SAVED EXPLANATION");
        browser.findElement(By.cssSelector("#decisionReasonDialog button")).click();
        browser.executeScript("document.querySelector(\"[data-diagram-node='fixture-second'] .dm-reason-action\").dispatchEvent(new MouseEvent('click',{bubbles:true}))");
        assertThat(browser.findElement(By.id("decisionReasonDialog")).getText()).contains("views.diagram.reasonMissing");
    }
    @Test void printUsesCompleteReadablePagesAndPreservesZoomCollapseAndStoredResults() throws Exception {
        browser.findElement(By.cssSelector("[data-diagram-node='fixture-parent']")).click(); settle();
        browser.executeScript("document.querySelector('.tax-diagram-controls button').click(); window.beforePrintState=JSON.stringify({data:fixtureData,scores:fixtureScores,reasons:TaxonomyState.currentReasons,svg:document.querySelector('#decision-map-svg').outerHTML}); window.printFrame=document.createElement('iframe'); document.body.append(printFrame); window.printCalls=0; window.open=()=>({document:printFrame.contentDocument,closed:false,focus(){},addEventListener(name,fn){window.afterPrint=fn},close(){window.closedPreview=true},requestAnimationFrame(fn){fn()},print(){window.printCalls++}});");
        browser.findElement(By.xpath("//button[text()='Print']")).click();
        assertThat(browser.executeScript("return printCalls")).isEqualTo(1L);
        assertThat(browser.executeScript("return printFrame.contentDocument.querySelectorAll('[data-print-node]').length")).isEqualTo(26L);
        assertThat(browser.executeScript("return printFrame.contentDocument.querySelectorAll('.decision-print-page').length>2")).isEqualTo(true);
        assertThat(browser.executeScript(PRINT_GEOMETRY)).isEqualTo("");
        assertThat(browser.executeScript("return printFrame.contentDocument.body.textContent.includes('75%') && printFrame.contentDocument.body.textContent.includes('fixture-deep')")).isEqualTo(true);
        assertThat(browser.executeScript("return beforePrintState===JSON.stringify({data:fixtureData,scores:fixtureScores,reasons:TaxonomyState.currentReasons,svg:document.querySelector('#decision-map-svg').outerHTML})")).isEqualTo(true);
        capturePrint();
        browser.executeScript("afterPrint()");
        assertThat(browser.executeScript("return !!closedPreview")).isEqualTo(true);
        browser.executeScript("window.open=()=>null"); browser.findElement(By.xpath("//button[text()='Print']")).click();
        assertThat(browser.findElement(By.cssSelector(".decision-print-status")).getText()).contains("views.diagram.printBlocked");
    }
    @Test void mixedAreaOnlyRelevanceSurvivesAlongsideConcreteMatchesOnScreenAndPrint() {
        browser.executeScript("""
            fixtureData=[{code:'BP',name:'Business Processes',taxonomyContainer:true,children:[{code:'fixture-positive',name:'Assessed process'}]},
                {code:'BR',name:'Business Roles',taxonomyContainer:true,children:[{code:'fixture-unassessed',name:'Unassessed role'}]}];
            fixtureScores={BP:90,BR:85,'fixture-positive':60};
            window.recordedMixedScores=JSON.stringify(fixtureScores);
            TaxonomyViews.renderDecisionMap(document.querySelector('#diagram'),fixtureData,fixtureScores);
            """); settle();
        assertThat(browser.findElements(By.cssSelector("[data-taxonomy-container='BP']"))).hasSize(1);
        assertThat(browser.findElements(By.cssSelector("[data-taxonomy-container='BR']"))).hasSize(1);
        assertThat(browser.findElement(By.cssSelector("[data-taxonomy-container='BR']")).getText()).contains("85");
        assertThat(browser.findElements(By.cssSelector("[data-diagram-node='BR'],[data-diagram-node='fixture-unassessed']"))).isEmpty();
        preparePrint(); clickPrint();
        assertThat(browser.executeScript("return [...printFrame.contentDocument.querySelectorAll('[data-taxonomy-panel]')].some(p=>p.dataset.taxonomyPanel==='BR' && p.textContent.includes('85'))")).isEqualTo(true);
        assertThat(browser.executeScript("return printFrame.contentDocument.querySelectorAll('[data-print-node=BR],[data-print-node=fixture-unassessed]').length")).isEqualTo(0L);
        assertThat(browser.executeScript("return JSON.stringify(fixtureScores)===recordedMixedScores")).isEqualTo(true);
    }
    @Test void fragmentedParentWithChildAndSiblingsKeepsEveryActualFragmentInsideItsPage() {
        browser.executeScript("""
            fixtureData=[{code:'BP',name:'Business Processes',taxonomyContainer:true,children:[
                {code:'fixture-long',name:'Exceptionally long complete node name '.repeat(80),children:[{code:'fixture-leaf',name:'Ordinary child'}]},
                {code:'fixture-sibling',name:'Ordinary sibling',children:[{code:'fixture-other-leaf',name:'Other ordinary child'}]}]}];
            fixtureScores={'fixture-long':75,'fixture-leaf':55,'fixture-sibling':45,'fixture-other-leaf':35};
            TaxonomyViews.renderDecisionMap(document.querySelector('#diagram'),fixtureData,fixtureScores);
            """); settle(); preparePrint(); clickPrint();
        assertThat(browser.executeScript("return printFrame.contentDocument.querySelectorAll('[data-print-fragment=fixture-long]').length>1")).isEqualTo(true);
        assertThat(browser.executeScript(PRINT_GEOMETRY)).isEqualTo("");
        assertThat(browser.executeScript("return ['fixture-long','fixture-leaf','fixture-sibling','fixture-other-leaf'].every(code=>printFrame.contentDocument.querySelectorAll('[data-print-node=\"'+code+'\"]').length===1)")).isEqualTo(true);
    }
    @Test void shortLabelConnectorTouchesTheVisibleParentAndChildBoundaries() throws Exception {
        browser.executeScript("""
            fixtureData=[{code:'BP',name:'Business Processes',taxonomyContainer:true,children:[{code:'fixture-parent',name:'Parent',children:[{code:'fixture-child',name:'Child'}]}]}];
            fixtureScores={'fixture-parent':60,'fixture-child':55};
            TaxonomyViews.renderDecisionMap(document.querySelector('#diagram'),fixtureData,fixtureScores);
            """); settle();
        assertThat(browser.executeScript("""
            for(const node of document.querySelectorAll('.dm-node')){
                const label=node.querySelector('.dm-label').getBBox(),card=node.querySelector('.dm-card');
                if(!card)return 'Visible card missing';
                const box=card.getBBox();
                if(label.y<box.y+2||label.y+label.height>box.y+box.height-2)return 'Label touches or crosses visible card border';
            }return '';
            """)).isEqualTo("");
        assertThat(browser.executeScript("""
            const parent=document.querySelector('[data-diagram-node=fixture-parent] .dm-card');
            const child=document.querySelector('[data-diagram-node=fixture-child] .dm-card');
            const path=document.querySelector('.dm-link');
            if(!parent||!child||!path)return 'Visible node boundaries are required';
            const first=path.getPointAtLength(0),last=path.getPointAtLength(path.getTotalLength());
            const p=parent.getBBox(),c=child.getBBox(),pd=parent.parentNode.__data__,cd=child.parentNode.__data__;
            if(Math.abs(first.x-(pd.y+p.x+p.width+1))>1||Math.abs(first.y-pd.x)>1)return 'Detached parent edge';
            if(Math.abs(last.x-(cd.y+c.x-1))>1||Math.abs(last.y-cd.x)>1)return 'Detached child edge';
            return '';
            """)).isEqualTo("");
        capture("decision-short-label-connectors.png");
        preparePrint(); clickPrint();
        assertThat(browser.executeScript("""
            const doc=printFrame.contentDocument;
            const parent=doc.querySelector('[data-print-node=fixture-parent] .decision-print-card');
            const child=doc.querySelector('[data-print-node=fixture-child] .decision-print-card');
            const path=doc.querySelector('svg path');
            if(!parent||!child||!path)return 'Visible printed node boundaries are required';
            const first=path.getPointAtLength(0),last=path.getPointAtLength(path.getTotalLength()),p=parent.getBBox(),c=child.getBBox();
            if(Math.abs(first.x-(p.x+p.width+1))>1||Math.abs(last.x-(c.x-1))>1)return 'Detached printed connector';
            return '';
            """)).isEqualTo("");
    }
    @Test void fourOrdinaryLevelsUseOneLandscapePageAtFullReadableSize() throws Exception {
        browser.executeScript("""
            fixtureData=[{code:'BP',name:'Business Processes',taxonomyContainer:true,children:[
                {code:'fixture-level-1',name:'Business processes',children:[
                    {code:'fixture-level-2',name:'Information services',children:[
                        {code:'fixture-level-3',name:'Analytical support',children:[
                            {code:'fixture-level-4',name:'Acquire data'}]}]}]}]}];
            fixtureScores={'fixture-level-1':75,'fixture-level-2':65,'fixture-level-3':55,'fixture-level-4':45};
            TaxonomyViews.renderDecisionMap(document.querySelector('#diagram'),fixtureData,fixtureScores);
            """); settle();
        browser.executeScript("window.fourLevelScreenBefore=document.querySelector('#decision-map-svg').outerHTML");
        preparePrint(); clickPrint();
        assertThat(browser.executeScript("return printFrame.contentDocument.querySelectorAll('.decision-print-page').length")).isEqualTo(1L);
        assertThat(browser.executeScript(PRINT_GEOMETRY)).isEqualTo("");
        assertThat(browser.executeScript("""
            const doc=printFrame.contentDocument,svg=doc.querySelector('svg');
            return doc.querySelectorAll('[data-print-node]').length===4
                && [...doc.querySelectorAll('svg text')].every(t=>Number(t.getAttribute('font-size'))>=14)
                && Math.abs(svg.getBoundingClientRect().width-svg.viewBox.baseVal.width)<0.01
                && document.querySelector('#decision-map-svg').outerHTML===fourLevelScreenBefore;
            """)).isEqualTo(true);
        String html=(String)browser.executeScript("return printFrame.contentDocument.documentElement.outerHTML");
        String source=browser.getWindowHandle(); browser.switchTo().newWindow(WindowType.TAB);
        try {
            browser.get("data:text/html;charset=UTF-8;base64,"+Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
            var cdp=(HasCdp)new Augmenter().augment(browser);
            var result=cdp.executeCdpCommand("Page.printToPDF",Map.of("landscape",true,"preferCSSPageSize",true,"printBackground",true));
            byte[] bytes=Base64.getDecoder().decode((String)result.get("data"));
            Path directory=Path.of("target/decision-diagram-evidence");Files.createDirectories(directory);
            Files.write(directory.resolve("decision-four-level-print.pdf"),bytes);
            Files.writeString(directory.resolve("decision-four-level-print.html"),html);
            try(var pdf=Loader.loadPDF(bytes)) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(1);
                assertThat(pdf.getPage(0).getMediaBox().getWidth()).isGreaterThan(pdf.getPage(0).getMediaBox().getHeight());
                var sizes=new ArrayList<Float>();
                new PDFTextStripper(){@Override protected void processTextPosition(TextPosition position){sizes.add(position.getFontSizeInPt());super.processTextPosition(position);}}.getText(pdf);
                assertThat(sizes).isNotEmpty().allSatisfy(size->assertThat(size).isGreaterThanOrEqualTo(10.5f));
            }
        } finally {browser.close();browser.switchTo().window(source);}
    }
    void preparePrint() {
        browser.executeScript("window.printFrame=document.createElement('iframe');document.body.append(printFrame);window.open=()=>({document:printFrame.contentDocument,closed:false,focus(){},addEventListener(){},requestAnimationFrame(fn){fn()},print(){}});");
    }
    void clickPrint() {browser.findElement(By.xpath("//button[text()='Print']")).click();}
    void capture(String name) throws Exception {
        Path directory=Path.of("target/decision-diagram-evidence"); Files.createDirectories(directory);
        Files.write(directory.resolve(name),browser.getScreenshotAs(OutputType.BYTES));
    }
    void capturePrint() throws Exception {
        String html=(String)browser.executeScript("return printFrame.contentDocument.documentElement.outerHTML");
        Path directory=Path.of("target/decision-diagram-evidence");Files.createDirectories(directory);
        Files.writeString(directory.resolve("decision-print.html"),html);
        if(browser instanceof ChromeDriver chrome){
            String source=browser.getWindowHandle();browser.switchTo().newWindow(WindowType.TAB);
            try {
                browser.get("data:text/html;charset=UTF-8;base64,"+Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
                var pdf=chrome.executeCdpCommand("Page.printToPDF",Map.of("landscape",true,"preferCSSPageSize",true,"printBackground",true));
                Files.write(directory.resolve("decision-print.pdf"),Base64.getDecoder().decode((String)pdf.get("data")));
                capture("decision-print-preview.png");
            } finally {browser.close();browser.switchTo().window(source);}
        }
    }
    void settle(){new WebDriverWait(browser,Duration.ofSeconds(5)).until(d->Boolean.TRUE.equals(browser.executeScript("return document.querySelector('#diagram').dataset.transitionsComplete==='true'")));}
    private static final String GEOMETRY="""
        const nodes=[...document.querySelectorAll('g.dm-node')].map(n=>({code:n.dataset.diagramNode,b:n.getBoundingClientRect()}));
        for(let i=0;i<nodes.length;i++)for(let j=i+1;j<nodes.length;j++){
          const a=nodes[i].b,b=nodes[j].b;
          if(a.left<b.right && b.left<a.right && a.top<b.bottom && b.top<a.bottom)return 'overlap '+nodes[i].code+' '+nodes[j].code;
        }
        for(const caption of document.querySelectorAll('.dm-area-caption'))for(const node of nodes){const a=caption.getBoundingClientRect(),b=node.b;if(a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom)return 'caption overlap';}
        const svg=document.querySelector('#decision-map-svg').getBoundingClientRect();
        for(const node of nodes)if(node.b.left<svg.left||node.b.right>svg.right||node.b.top<svg.top||node.b.bottom>svg.bottom)return 'clipped '+node.code;
        return '';
        """;
    private static final String PRINT_GEOMETRY="""
        const doc=printFrame.contentDocument;
        for(const page of doc.querySelectorAll('.decision-print-page')){
          const svg=page.querySelector('svg'),vb=svg.viewBox.baseVal;
          if(vb.width>1040||vb.height>620)return 'unreadable page scale';
          for(const node of svg.querySelectorAll('[data-print-fragment]')){const b=node.getBBox();if(b.x<0||b.y<0||b.x+b.width>vb.width||b.y+b.height>vb.height)return 'clipped '+node.dataset.printFragment+' at '+b.x+','+b.y+' in '+vb.width+'x'+vb.height;}
        }return '';
        """;
    static void serve(HttpExchange exchange) throws java.io.IOException {
        String path=exchange.getRequestURI().getPath();byte[] bytes;
        if(path.equals("/fixture")){bytes=HTML.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");}
        else if(path.equals("/views.js")){bytes=Files.readAllBytes(Path.of("src/main/resources/static/js/core/taxonomy-views.js"));exchange.getResponseHeaders().set("Content-Type","application/javascript");}
        else if(path.equals("/d3.js")){bytes=new ClassPathResource("META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js").getInputStream().readAllBytes();exchange.getResponseHeaders().set("Content-Type","application/javascript");}
        else {exchange.sendResponseHeaders(404,-1);exchange.close();return;}
        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    }
    static final String HTML="""
        <!doctype html><html><head><meta charset="UTF-8"><style>body{margin:16px;font-family:Arial}#diagram{width:1000px}.decision-tree-wrapper{overflow:auto}.tax-diagram-controls{display:flex;gap:8px;align-items:center}.tax-reason-dialog{max-width:650px;max-height:80vh}.tax-reason-text{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body><div id="diagram"></div><script src="/d3.js"></script><script>
        window.TaxonomyI18n={t:(key,...args)=>key==='views.diagram.print'?'Print':key==='views.diagram.rationale'?'View rationale':key+' '+args.join(' / ')};
        window.TaxonomyUtils={isTaxonomyContainer:n=>!!n.taxonomyContainer,contentRoots:items=>items.flatMap(n=>n.taxonomyContainer?n.children:[n])};
        window.TaxonomyState={currentReasons:{'fixture-parent':'SAVED EXPLANATION '+('Full reason. '.repeat(100))+'\\nFinal saved line.'}};
        window.fixtureData=[{code:'CP',name:'Capability area '+('long area name '.repeat(18)),taxonomyContainer:true,children:[{code:'fixture-parent',name:'Long parent label '+('multiline words '.repeat(20)),description:'CATALOGUE ONLY',children:Array.from({length:22},(_,i)=>({code:'fixture-child-'+i,name:'Detailed child label '+('complete name '.repeat(12)),children:i===0?[{code:'fixture-deep',name:'Deep branch',children:[{code:'fixture-deeper',name:'Final deep node'}]}]:[]}))}]},{code:'BR',name:'Roles area',taxonomyContainer:true,children:[{code:'fixture-second',name:'Second taxonomy '+('long words '.repeat(22))}]}];
        window.fixtureScores={};function score(n){if(!n.taxonomyContainer)fixtureScores[n.code]=75;(n.children||[]).forEach(score)}fixtureData.forEach(score);
        window.fetch=()=>{throw new Error('No analysis or provider requests allowed')};
        </script><script src="/views.js"></script><script>TaxonomyViews.renderDecisionMap(document.querySelector('#diagram'),fixtureData,fixtureScores);</script></body></html>
        """;
}
