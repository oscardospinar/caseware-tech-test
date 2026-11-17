package com.caseware.fanout.adapter.out.demo;

import java.util.List;
import com.caseware.fanout.application.port.out.Inventory;
import com.caseware.fanout.domain.model.Candidate;
import com.caseware.fanout.domain.model.Engagement;
import com.caseware.fanout.domain.model.Page;
import com.caseware.fanout.domain.model.Publication;

public final class DemoInventory implements Inventory {
    private final List<Candidate> files;
    public DemoInventory(String region) {
        this.files = List.of(
                new Candidate(new Engagement(region, "firm-a", "a1"), 1, true),
                new Candidate(new Engagement(region, "firm-a", "a2"), 1, true),
                new Candidate(new Engagement(region, "firm-b", "b1"), 1, true),
                new Candidate(new Engagement(region, "firm-b", "healthy"), 1, false));
    }

    @Override public Page fetch(Publication publication, String cursor, int limit) {
        int start = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(files.size(), start + limit);
        return new Page(files.subList(start, end), end == files.size() ? null : Integer.toString(end));
    }
}
