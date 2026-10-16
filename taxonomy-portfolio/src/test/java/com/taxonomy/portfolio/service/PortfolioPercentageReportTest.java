package com.taxonomy.portfolio.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PortfolioPercentageReportTest {
    @ParameterizedTest
    @CsvSource({
            "MARKDOWN,0.125,0.125%", "DOCX,0.125,0.125%",
            "MARKDOWN,0.00000001,<0.000001%", "DOCX,0.00000001,<0.000001%",
            "MARKDOWN,0,0%", "DOCX,0,0%",
            "MARKDOWN,85,85%", "DOCX,85,85%"
    })
    void preservesFractionalTaxonomyCoverage(PortfolioReportService.Format format,
                                            double percentage, String expected) throws Exception {
        PortfolioPercentageReportChecks.check(format, percentage, expected);
    }
}
