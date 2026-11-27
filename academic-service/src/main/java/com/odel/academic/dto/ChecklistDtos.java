package com.odel.academic.dto;

import java.util.List;

/** The setup checklist (W-10). */
public final class ChecklistDtos {
    private ChecklistDtos() {}

    public record Step(int number, String key, String title, String summary, String status, String actionLabel, String href, String lockedReason) {}

    public record Checklist(String sessionName, List<Step> steps, int done, int total, int percent) {}
}
