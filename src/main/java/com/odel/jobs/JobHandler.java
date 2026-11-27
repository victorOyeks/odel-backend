package com.odel.jobs;

import com.fasterxml.jackson.databind.JsonNode;

/** One kind of background work. Handlers must be safe to run twice (a worker can crash mid-job). */
public interface JobHandler {
    String type();

    void handle(JsonNode payload) throws Exception;
}
