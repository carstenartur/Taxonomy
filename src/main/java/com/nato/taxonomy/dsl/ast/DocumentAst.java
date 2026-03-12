package com.nato.taxonomy.dsl.ast;

import java.util.List;
import java.util.Objects;

/**
 * Root node of a parsed DSL document.
 *
 * <p>A document consists of an optional {@link MetaAst} header followed by
 * zero or more {@link BlockAst} entries. Unknown block types are preserved
 * in the block list so that round-trips are lossless.
 */
public final class DocumentAst {

    private final MetaAst meta;
    private final List<BlockAst> blocks;

    public DocumentAst(MetaAst meta, List<BlockAst> blocks) {
        this.meta = meta;
        this.blocks = List.copyOf(Objects.requireNonNull(blocks));
    }

    public MetaAst getMeta() { return meta; }
    public List<BlockAst> getBlocks() { return blocks; }

    /** Returns all blocks of a given kind (e.g. {@code "element"}, {@code "relation"}). */
    public List<BlockAst> blocksOfKind(String kind) {
        return blocks.stream().filter(b -> b.getKind().equals(kind)).toList();
    }
}
