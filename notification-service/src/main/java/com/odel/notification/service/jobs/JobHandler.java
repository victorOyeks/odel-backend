package com.odel.notification.service.jobs;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.fasterxml.jackson.databind.JsonNode;

/** One kind of background work. Handlers must be safe to run twice (a worker can crash mid-job). */
public interface JobHandler {
    String type();

    void handle(JsonNode payload) throws Exception;
}
