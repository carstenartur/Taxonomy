package com.taxonomy.security.controller;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.access.ApiAccessPolicy;
import com.taxonomy.security.access.ApiPermission;
import com.taxonomy.security.service.RepositoryOwnershipTransfer;
import com.taxonomy.workspace.controller.ArchitectureRepositoryController;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RepositoryOwnershipTransferControllerTest {
    @Test void transferUsesTheVersionFromTheVisibleRepositoryHttpRepresentation() throws Exception {
        var login = new TestingAuthenticationToken("owner", "unused");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn(login.getName());
        var catalogue = mock(SystemRepositoryService.class);
        var memberships = mock(RepositoryMembershipService.class);
        var repository = new SystemRepository();
        repository.setRepositoryId("project");
        repository.setOwnerId("owner");
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"version",13L);
        when(catalogue.getRepository("project")).thenReturn(repository);
        when(memberships.canRead(repository,"owner")).thenReturn(true);
        var service = mock(RepositoryOwnershipTransfer.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new ArchitectureRepositoryController(catalogue,null,null,memberships,resolver),
                new RepositoryOwnershipTransferController(service)).build();
        String path = "/api/repositories/project";
        String json = mvc.perform(get(path).principal(login)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("owner")).andExpect(jsonPath("$.version").value(13))
                .andReturn().getResponse().getContentAsString();
        long revision = JsonMapper.builder().build().readTree(json).get("version").longValue();
        var successor = UUID.randomUUID();
        var receipt = new RepositoryOwnershipTransfer.Receipt(UUID.randomUUID(),UUID.randomUUID(),successor,14L);
        when(service.transfer(login,"project",new PrincipalId(successor),revision,"handoff")).thenReturn(receipt);
        mvc.perform(put(path + "/ownership").principal(login).contentType("application/json").content("""
                {"targetPrincipalId":"%s","expectedVersion":%d,"rationale":"handoff"}
                """.formatted(successor,revision))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(14));
        verify(service).transfer(login,"project",new PrincipalId(successor),13L,"handoff");
    }

    @Test void ordinaryHttpRouteRequiresTheRepositoryPermissionAndReturnsTheCommittedReceipt() throws Exception {
        String path = "/api/repositories/project/ownership";
        assertThat(ApiAccessPolicy.resolve("PUT",path).permission()).isEqualTo(ApiPermission.REPOSITORY_MANAGE);
        var service = mock(RepositoryOwnershipTransfer.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RepositoryOwnershipTransferController(service)).build();
        var login = new TestingAuthenticationToken("owner", "unused");
        var target = UUID.randomUUID();
        var receipt = new RepositoryOwnershipTransfer.Receipt(UUID.randomUUID(),UUID.randomUUID(),target,8L);
        when(service.transfer(login,"project",new PrincipalId(target),7L,"handoff")).thenReturn(receipt);
        mvc.perform(put(path).principal(login).contentType("application/json").content("""
                {"targetPrincipalId":"%s","expectedVersion":7,"rationale":"handoff"}
                """.formatted(target))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.auditId").value(receipt.auditId().toString())).andExpect(jsonPath("$.version").value(8));
        mvc.perform(put(path).principal(login).contentType("application/json").content("{}"))
                .andExpect(status().is(428));
        mvc.perform(put(path).principal(login).contentType("application/json").content("{\"expectedVersion\":7}"))
                .andExpect(status().isBadRequest());
        verify(service).transfer(login,"project",new PrincipalId(target),7L,"handoff");
        verifyNoMoreInteractions(service);
    }
}
