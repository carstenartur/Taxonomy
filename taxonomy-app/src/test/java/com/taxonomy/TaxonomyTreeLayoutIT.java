package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

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

    @BeforeEach void fixture() { driver.get(session.origin() + "/fixture"); }

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

    @Test void defaultExportKeepsDeeperBranchesCollapsed() {
        Object exported = driver.executeScript("""
                var data=[{code:'fixture-1',children:[{code:'fixture-2',children:[
                  {code:'fixture-3',children:[{code:'fixture-4',children:[{code:'fixture-5'}]}]}]}]}];
                return TaxonomyViews.buildExportSVG(data,{},{}).textContent;
                """);
        assertThat(exported.toString()).contains("fixture-3").doesNotContain("fixture-4", "fixture-5");
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
