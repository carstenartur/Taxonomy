package com.taxonomy.portfolio.inventory;
import com.taxonomy.portfolio.dto.PortfolioDtos.ProductView;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.extension.api.inventory.InventoryComparisonContracts.*;
import com.taxonomy.extension.api.inventory.InventoryReadContracts.AssertionKind;
import java.util.*;

/** Sourced catalogue claims are distinct from explicit organizational availability. */
public final class CuratedInventoryProjection {
    public Candidate project(ProductView product,String fingerprint,List<Assertion> retained,Question question) {
        String id="product:"+product.id(); var assertions=new ArrayList<>(retained);var metadata=new TreeMap<String,String>();
        metadata.put("source",Objects.toString(product.sourceReference(),""));
        metadata.put("verifiedAt",Objects.toString(product.verifiedAt(),""));
        metadata.put("productStatus",Objects.toString(product.productStatus(),""));
        metadata.put("editionVersion",Objects.toString(product.editionVersion(),""));
        // Coverage is a reviewed semantic mapping, not proof of availability or implementation.
        for(var mapping:product.taxonomyCoverage()) if(mapping.reviewStatus()==ReviewStatus.CONFIRMED) {
            assertions.add(new Assertion("mapping:"+mapping.id(),id,Criterion.CAPABILITY,mapping.nodeCode(),mapping.coveragePercent()>0,
                    null,null,null,null,AssertionKind.DECLARED,
                    Objects.toString(mapping.evidence(),"Confirmed taxonomy mapping"),mapping.updatedAt()));
        }
        if(product.endOfSupport()!=null) {
            // A support end can contradict future support, but no start date is manufactured.
            assertions.add(new Assertion("support:"+product.id(),id,Criterion.SUPPORT,null,true,null,
                    new Boundary(product.endOfSupport().toString(),Precision.DATE),question.organization(),question.environment(),
                    AssertionKind.DECLARED,Objects.toString(product.sourceReference(),"Catalogue support claim"),product.verifiedAt()));
        }
        return new Candidate(id,product.id(),product.productName(),"TECHNOLOGY_PRODUCT",fingerprint,metadata,assertions);
    }
}
