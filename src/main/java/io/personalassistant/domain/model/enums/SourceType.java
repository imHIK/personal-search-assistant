package io.personalassistant.domain.model.enums;

public enum SourceType {
    LOCAL_FS,
    GMAIL,
    SLACK,
    GOOGLE_DRIVE,
    NOTION,
    // One type across every ATS platform: which one a company uses is resolved per company at discover.
    JOB_BOARDS
}
