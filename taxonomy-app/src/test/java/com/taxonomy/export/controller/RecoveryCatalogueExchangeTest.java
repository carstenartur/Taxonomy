package com.taxonomy.export.controller;

import org.junit.jupiter.api.Test;

class RecoveryCatalogueExchangeTest {
    @Test void importRejectsInventedCoverageIdentity() throws Exception { RecoveryCatalogueExchangeProbe.verify("import",true,false); }
    @Test void exportRejectsInventedCoverageIdentity() throws Exception { RecoveryCatalogueExchangeProbe.verify("export",true,false); }
    @Test void importPreservesRealUnassessedScope() throws Exception { RecoveryCatalogueExchangeProbe.verify("import",false,false); }
    @Test void exportPreservesRealUnassessedScope() throws Exception { RecoveryCatalogueExchangeProbe.verify("export",false,false); }
    @Test void legacyUnknownCodesRemainCompatible() throws Exception { RecoveryCatalogueExchangeProbe.verify("import",true,true); }
}
