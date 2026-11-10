package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeliveryGuardMigrationTest {
  @Test
  void releasePolicyRemainsRunnableWithoutAnInterpreter() {
    var r = QualitySiteTest.run("check-release-delivery-contract");
    assertThat(r.code()).as(r.output()).isZero();
  }

  @Test
  void hardeningPolicyRemainsRunnableWithoutAnInterpreter() {
    var r = QualitySiteTest.run("check-delivery-hardening");
    assertThat(r.code()).as(r.output()).isZero();
  }
}
