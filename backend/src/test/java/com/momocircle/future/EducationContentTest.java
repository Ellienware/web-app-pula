package com.momocircle.future;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class EducationContentTest {
    @Test void lessonIdsAreUniqueAndNonEmpty() {
        var ids = EducationContent.lessons().stream().map(EducationContent.Lesson::id).toList();
        assertFalse(ids.isEmpty()); assertEquals(ids.size(), ids.stream().distinct().count());
        EducationContent.lessons().forEach(l -> { assertFalse(l.body().isEmpty()); assertFalse(l.summary().isBlank()); });
    }
    /** Content must never promise returns or name regulated products/providers as a recommendation. */
    @Test void noGuaranteesOrRecommendations() {
        for (var l : EducationContent.lessons()) {
            String all = (l.title() + " " + l.summary() + " " + String.join(" ", l.body())).toLowerCase(Locale.ROOT);
            assertFalse(all.contains("guaranteed return") && !l.id().equals("spotting-scams") && !l.id().equals("risk-and-return"),
                    "Unexpected guarantee language in " + l.id());
            assertFalse(all.contains("you should buy") || all.contains("we recommend"), "Recommendation language in " + l.id());
        }
        assertTrue(EducationContent.DISCLAIMER.toLowerCase(Locale.ROOT).contains("not a financial adviser"));
    }
}
