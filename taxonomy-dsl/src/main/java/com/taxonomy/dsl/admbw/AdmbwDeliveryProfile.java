package com.taxonomy.dsl.admbw;

import com.taxonomy.dsl.ast.BlockAst;
import com.taxonomy.dsl.ast.PropertyAst;
import com.taxonomy.dsl.model.ArchitectureView;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The FFFmLV delivery configuration retained in the architecture revision. */
public record AdmbwDeliveryProfile(String profile, String projectCode, String sourceViewId, String catalogueFingerprint) {
    public static final String BLOCK_ID = "admbw";
    public static final String BLOCK_KEY = "delivery:" + BLOCK_ID;
    private static final String CATALOGUE_PROVENANCE = "x-analysis-catalogue-fingerprint";
    private static final Set<String> PROPERTIES = Set.of("profile", "project-code", "source-view", "catalogue-fingerprint");

    public AdmbwDeliveryProfile {
        if (!AdmbwFffmlvProfile.ID.equals(profile))
            throw new IllegalArgumentException("Unsupported ADMBw delivery profile");
        if (projectCode == null || !projectCode.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Project code must have 1–32 letters, digits, hyphens or underscores");
        if (sourceViewId != null && !sourceViewId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid ADMBw source view identity");
        if (catalogueFingerprint != null && (sourceViewId == null || !catalogueFingerprint.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Invalid retained C3 catalogue fingerprint for ADMBw delivery");
    }

    public static AdmbwDeliveryProfile fromBlock(BlockAst block) {
        if (!List.of(BLOCK_ID).equals(block.getHeaderTokens())
                || block.getProperties().stream().anyMatch(p -> !PROPERTIES.contains(p.key()))
                || block.getProperties().stream().map(p -> p.key()).distinct().count() != block.getProperties().size())
            throw new IllegalArgumentException("Invalid ADMBw delivery configuration");
        return new AdmbwDeliveryProfile(block.property("profile"), block.property("project-code"),
                block.property("source-view"), block.property("catalogue-fingerprint"));
    }

    public static AdmbwDeliveryProfile configure(CanonicalArchitectureModel model, String projectCode, String viewId) {
        var view = viewId == null ? null : selectedView(model, viewId);
        return new AdmbwDeliveryProfile(AdmbwFffmlvProfile.ID, projectCode, viewId,
                view == null ? null : view.getExtensions().get(CATALOGUE_PROVENANCE));
    }

    public BlockAst toBlock() {
        var properties = new ArrayList<PropertyAst>();
        properties.add(new PropertyAst("profile", profile, null));
        properties.add(new PropertyAst("project-code", projectCode, null));
        if (sourceViewId != null) properties.add(new PropertyAst("source-view", sourceViewId, null));
        if (catalogueFingerprint != null) properties.add(new PropertyAst("catalogue-fingerprint", catalogueFingerprint, null));
        return new BlockAst("delivery", List.of(BLOCK_ID), properties, List.of(), Map.of(), null);
    }

    public Set<String> members(CanonicalArchitectureModel model) {
        if (sourceViewId == null) return model.allElementIds();
        var view = selectedView(model, sourceViewId);
        if (!Objects.equals(catalogueFingerprint, view.getExtensions().get(CATALOGUE_PROVENANCE)))
            throw new IllegalArgumentException("The saved ADMBw delivery and source catalogue evidence differ");
        return Set.copyOf(view.getIncludes());
    }

    void requireRequest(String requestedProject, String requestedView) {
        if (!projectCode.equals(requestedProject) || !Objects.equals(sourceViewId, requestedView))
            throw new IllegalArgumentException("Use the project code and source view of the saved ADMBw delivery");
    }

    private static ArchitectureView selectedView(CanonicalArchitectureModel model, String id) {
        var views = model.getViews().stream().filter(view -> id.equals(view.getId())).toList();
        if (views.size() != 1) throw new IllegalArgumentException("Select one existing canonical view for ADMBw delivery");
        return views.getFirst();
    }
}
