package com.taxonomy.backup.restore;

import java.util.Objects;

/** Stable private operator diagnostics; never raw SQL, connection URLs or secrets. */
public record RestoreDiagnostic(String code,Severity severity,String owner,String resource) {
    public enum Severity {BLOCKER,INFORMATION}
    public RestoreDiagnostic {
        if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("Invalid restore diagnostic");
        Objects.requireNonNull(severity);
        for(String text:new String[]{owner,resource})if(text!=null&&(text.length()>4096||text.chars().anyMatch(Character::isISOControl)))throw new IllegalArgumentException("Invalid restore diagnostic");
    }
}
