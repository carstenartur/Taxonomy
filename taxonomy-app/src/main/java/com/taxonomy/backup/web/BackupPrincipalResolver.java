package com.taxonomy.backup.web;

import com.taxonomy.backup.PrincipalId;
import org.springframework.security.core.Authentication;

/** Identity adapter is supplied by application composition, avoiding a backup/security package cycle. */
@FunctionalInterface
public interface BackupPrincipalResolver { PrincipalId require(Authentication authentication); }
