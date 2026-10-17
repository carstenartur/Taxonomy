package com.taxonomy;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.testsupport.BrowserSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Native dialog behavior with the production script and deterministic source metadata. */
@Tag("browser")
@Tag("ui-acceptance")
class DecisionExportPresentationUiIT {
    @TempDir static Path downloads;
    private static HttpServer server;
    private static BrowserSession session;
    private static RemoteWebDriver browser;

    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/", exchange -> {
            boolean script = exchange.getRequestURI().getPath().equals("/dialog.js");
            byte[] bytes;
            if (script) {
                try (var source = new ClassPathResource("static/js/shared/decision-export-dialog.js").getInputStream()) {
                    bytes = source.readAllBytes();
                }
            } else {
                bytes = """
                        <!doctype html><html lang="de"><head><meta charset="utf-8"></head><body>
                        <button id="open" type="button">Entscheidungsbericht</button>
                        <script>
                        window.TaxonomyCapabilities={
                            load:async()=>({reports:{}}),
                            formats:()=>[{id:'docx',displayName:'Word',fileExtension:'docx'}]
                        };
                        </script><script src="/dialog.js"></script><script>
                        document.getElementById('open').addEventListener('click',()=>TaxonomyDecisionExport.open({
                            language:'de',saved:true,source:'Deterministic saved result fixture',
                            roots:[{code:'BP',title:'BP',inAnalysisScope:true},{code:'BR',title:'BR',inAnalysisScope:true},
                                   {code:'CP',title:'CP',inAnalysisScope:false}],
                            analysisScope:{taxonomyRoots:['BP','BR'],mode:'TAXONOMIES_ONLY'},
                            submit:async(request)=>{window.submittedOptions=request.options;}
                        }));
                        </script></body></html>
                        """.getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", script ? "text/javascript;charset=UTF-8" : "text/html;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        session = BrowserSession.open(server.getAddress().getPort(), downloads);
        browser = session.driver();
    }

    @AfterAll static void close() {
        try { if (session != null) session.close(); }
        finally { if (server != null) server.stop(0); }
    }

    @BeforeEach void openDialog() {
        browser.get(session.origin() + "/fixture");
        browser.findElement(By.id("open")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ignored ->
                browser.findElement(By.cssSelector(".decision-export-dialog [type=submit]")).isEnabled());
    }

    @Test void startsWithGraphicalOverviewAndDetailedReasons() {
        assertThat(select("profile").getFirstSelectedOption().getDomAttribute("value")).isEqualTo("STANDARD");
        assertThat(select("treeLayout").getFirstSelectedOption().getDomAttribute("value")).isEqualTo("AUTO");
        assertThat(browser.findElement(By.cssSelector("[name=section][value=CHAPTERS]")).isSelected()).isTrue();
        assertThat(browser.findElement(By.cssSelector("[name=section][value=ARCHITECTURE]")).isEnabled()).isFalse();
    }

    @Test void changingContentDepthRetainsChosenGraphicLayoutAndContents() {
        browser.findElement(By.cssSelector(".decision-export-dialog summary")).click();
        select("treeLayout").selectByValue("A4_LANDSCAPE");
        select("contents").selectByValue("NONE");
        select("profile").selectByValue("FULL");
        assertThat(select("treeLayout").getFirstSelectedOption().getDomAttribute("value")).isEqualTo("A4_LANDSCAPE");
        assertThat(select("contents").getFirstSelectedOption().getDomAttribute("value")).isEqualTo("NONE");
        assertThat(browser.findElement(By.cssSelector("[name=section][value=EVIDENCE]")).isSelected()).isTrue();
        assertThat(browser.findElement(By.cssSelector("[name=section][value=ARCHITECTURE]")).isSelected()).isFalse();
        select("profile").selectByValue("COMPACT");
        assertThat(select("treeLayout").getFirstSelectedOption().getDomAttribute("value")).isEqualTo("A4_LANDSCAPE");
        assertThat(browser.findElement(By.cssSelector("[name=section][value=CHAPTERS]")).isSelected()).isFalse();
    }

    @Test void defaultExportRetainsRecordedScopeAndRestoresFocus() {
        browser.findElement(By.cssSelector(".decision-export-dialog [type=submit]")).click();
        new WebDriverWait(browser, Duration.ofSeconds(10)).until(ignored ->
                browser.executeScript("return !!window.submittedOptions").equals(true));
        assertThat(browser.executeScript("return submittedOptions.profile")).isEqualTo("STANDARD");
        assertThat(browser.executeScript("return submittedOptions.treeLayout")).isEqualTo("AUTO");
        assertThat(browser.executeScript("return submittedOptions.taxonomyRoots")).isEqualTo(List.of("BP", "BR"));
        assertThat(browser.switchTo().activeElement().getDomAttribute("id")).isEqualTo("open");
    }

    private static Select select(String name) {
        return new Select(browser.findElement(By.cssSelector(".decision-export-dialog [name=" + name + "]")));
    }
}
