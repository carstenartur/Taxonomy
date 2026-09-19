package com.taxonomy.portfolio.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PortfolioJsonCodecBoundaryTest {
    final PortfolioJsonCodec codec=new PortfolioJsonCodec(new ObjectMapper());
    @Test void optionalPayloadsRemainAbsentAndCollectionsRemainImmutable() {
        assertThat(codec.write(null)).isNull();
        for(String empty:new String[]{null,""," "}) {
            assertThat(codec.read(empty,Map.class)).isNull();
            assertThat(codec.readLongList(empty)).isEmpty();assertThat(codec.readStringMap(empty)).isEmpty();
        }
        assertThat(codec.read(codec.write(Map.of("key","value")),Map.class)).containsEntry("key","value");
        var ids=codec.readLongList("[1,2,2]");assertThat(ids).containsExactly(1L,2L,2L);
        assertThatThrownBy(()->ids.add(3L)).isInstanceOf(UnsupportedOperationException.class);
        var attributes=codec.readStringMap("{\"key\":\"value\"}");assertThat(attributes).containsEntry("key","value");
        assertThatThrownBy(()->attributes.put("other","v")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void invalidPayloadsBecomeTypedAnalysisFailures() {
        assertThatThrownBy(()->codec.read("{",Map.class)).isInstanceOf(PortfolioException.class).hasMessageContaining("deserialize portfolio payload");
        assertThatThrownBy(()->codec.readLongList("{}" )).isInstanceOf(PortfolioException.class).hasMessageContaining("source fragment IDs");
        assertThatThrownBy(()->codec.readStringMap("[]")).isInstanceOf(PortfolioException.class).hasMessageContaining("extension attributes");
        var mapper=mock(ObjectMapper.class);var failure=new IllegalArgumentException("serialization failure");
        when(mapper.writeValueAsString("payload")).thenThrow(failure);
        assertThatThrownBy(()->new PortfolioJsonCodec(mapper).write("payload"))
                .isInstanceOf(PortfolioException.class).hasCause(failure);
    }
}
