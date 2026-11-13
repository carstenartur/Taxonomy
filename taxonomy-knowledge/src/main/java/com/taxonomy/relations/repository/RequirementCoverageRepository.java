package com.taxonomy.relations.repository;

import com.taxonomy.relations.model.RequirementCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface RequirementCoverageRepository extends JpaRepository<RequirementCoverage, Long> {
    String AT_SOURCE = " c.repositoryId=:repo and c.workspaceScope=:workspace and c.branch=:branch and c.sourceCommit=:commit ";

    @Query("from RequirementCoverage c where" + AT_SOURCE + "and c.nodeCode=:code order by c.requirementId")
    List<RequirementCoverage> forNode(String repo, String workspace, String branch, String commit, String code);

    @Query("from RequirementCoverage c where" + AT_SOURCE + "and c.requirementId=:requirement order by c.nodeCode")
    List<RequirementCoverage> forRequirement(String repo, String workspace, String branch, String commit, String requirement);

    @Query("select c.nodeCode,count(c) from RequirementCoverage c where" + AT_SOURCE + "group by c.nodeCode")
    List<Object[]> density(String repo, String workspace, String branch, String commit);

    @Query("select count(distinct c.requirementId) from RequirementCoverage c where" + AT_SOURCE)
    long requirements(String repo, String workspace, String branch, String commit);

    @Query("select count(distinct c.nodeCode) from RequirementCoverage c where" + AT_SOURCE + "and c.score >= :threshold")
    long coveredNodes(String repo, String workspace, String branch, String commit, int threshold);

    @Modifying
    @Query("delete from RequirementCoverage c where" + AT_SOURCE + "and c.requirementId=:requirement")
    int remove(String repo, String workspace, String branch, String commit, String requirement);
}
