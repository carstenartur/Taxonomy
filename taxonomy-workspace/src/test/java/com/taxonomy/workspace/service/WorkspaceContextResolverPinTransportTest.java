package com.taxonomy.workspace.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceContextResolverPinTransportTest {

    @Test
    void headerWinsOverQueryAndIsCanonicalized() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, "  workspace-header  ");
        request.addParameter(
                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "workspace-query");

        assertEquals(
                "workspace-header",
                WorkspaceContextResolver.requestedWorkspaceId(request));
    }

    @Test
    void blankHeaderExplicitlyOverridesStaleQuery() {
        var request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, "  ");
        request.addParameter(
                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "workspace-query");

        assertEquals("", WorkspaceContextResolver.requestedWorkspaceId(request));
    }

    @Test
    void queryIsUsedOnlyWhenHeaderIsAbsent() {
        var request = new MockHttpServletRequest();
        request.addParameter(
                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "  workspace-query  ");

        assertEquals(
                "workspace-query",
                WorkspaceContextResolver.requestedWorkspaceId(request));
    }

    @Test
    void absentPinRemainsAbsent() {
        assertNull(WorkspaceContextResolver.requestedWorkspaceId(
                new MockHttpServletRequest()));
    }
}
