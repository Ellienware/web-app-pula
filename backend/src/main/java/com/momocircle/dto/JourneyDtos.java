package com.momocircle.dto;

import java.util.List;

public class JourneyDtos {
    /** One stage of the PULA business lifecycle. Completion is derived server-side from real records. */
    public record JourneyStage(String key, String title, String description, boolean done, String route) {}
    public record BusinessJourney(boolean hasBusiness, String businessName, List<JourneyStage> stages,
                                  int doneCount, String nextStageKey, String note) {}
}
