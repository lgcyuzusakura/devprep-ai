package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.domain.ChunkHit;
import com.devprep.ai.domain.QaResult;
import com.devprep.ai.model.ModelGateway;
import com.devprep.ai.model.ModelSettingsService;
import com.devprep.ai.config.DevPrepProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QaServiceTest {
    @TempDir Path temporary;
    private MaterialService materials;
    private ModelSettingsService settings;
    private ModelGateway gateway;
    private QaService service;

    @BeforeEach
    void setUp() {
        materials = mock(MaterialService.class);
        gateway = mock(ModelGateway.class);
        DevPrepProperties properties = new DevPrepProperties();
        properties.setConfigFile(temporary.resolve("settings.toml").toString());
        settings = new ModelSettingsService(properties, name -> null, temporary.resolve("codex"));
        service = new QaService(materials, settings, gateway);
    }

    @Test
    void noRelevantMaterialsReturnsWithoutCallingModelGateway() {
        when(materials.search("JVM 内存模型", 6)).thenReturn(List.of());

        QaResult result = service.answer("  JVM 内存模型  ");

        assertFalse(result.grounded());
        assertTrue(result.citations().isEmpty());
        assertNull(result.model_used());
        verify(materials).search("JVM 内存模型", 6);
        verifyNoInteractions(gateway);
    }

    @Test
    void answerCarriesRetrievedSourcesAndOnlyMarksInRangeCitationNumbersGrounded() {
        ChunkHit hit = hit("chunk-1", "material-1", "Java 并发", "happens-before", "可见性由 happens-before 规则保证。");
        when(materials.search("happens-before", 6)).thenReturn(List.of(hit));
        when(gateway.generate(any(), eq("happens-before"), anyList())).thenReturn("[1] 建立 happens-before 关系后可见。");

        QaResult result = service.answer("happens-before");

        assertEquals("[1] 建立 happens-before 关系后可见。", result.answer());
        assertEquals(1, result.citations().size());
        assertEquals("chunk-1", result.citations().get(0).id());
        assertTrue(result.grounded());
        verify(gateway).generate(any(), eq("happens-before"), argThat(passages ->
                passages.size() == 1 && passages.get(0).get("content").equals("可见性由 happens-before 规则保证。")));

        when(gateway.generate(any(), eq("happens-before"), anyList())).thenReturn("[2] 这条引用超出了资料范围。");
        assertFalse(service.answer("happens-before").grounded());
    }

    @Test
    void missingModelFallsBackToSearchResultsAndDoesNotExposeProviderFailure() {
        when(materials.search("线程安全", 6)).thenReturn(List.of(
                hit("chunk-1", "material-1", "Java 并发", "线程安全", "线程安全需要保护共享状态。")));
        when(gateway.generate(any(), eq("线程安全"), anyList()))
                .thenThrow(new ApiException(400, "model_not_configured", "private diagnostic"));

        QaResult result = service.answer("线程安全");

        assertFalse(result.grounded());
        assertNull(result.model_used());
        assertEquals(1, result.citations().size());
        assertTrue(result.answer().contains("当前模型尚未配置"));
        assertFalse(result.answer().contains("private diagnostic"));
    }

    @Test
    void invalidQuestionIsRejectedBeforeSearching() {
        ApiException blank = assertThrows(ApiException.class, () -> service.answer("  "));
        assertEquals("invalid_question", blank.getCode());
        ApiException oversized = assertThrows(ApiException.class, () -> service.answer("x".repeat(4001)));
        assertEquals("invalid_question", oversized.getCode());
        ApiException malformedUnicode = assertThrows(ApiException.class, () -> service.answer("\ud800"));
        assertEquals("invalid_question", malformedUnicode.getCode());
        verifyNoInteractions(materials, gateway);
    }

    @Test
    void citationNumbersAboveThreeDigitsOrIntegerRangeCannotBeIgnored() {
        assertTrue(QaService.validReferences("[1]", 1));
        assertFalse(QaService.validReferences("[1000]", 1));
        assertFalse(QaService.validReferences("[1] and [1000]", 1));
        assertFalse(QaService.validReferences("[999999999999999999999999]", 1));
    }

    private static ChunkHit hit(String id, String materialId, String title, String heading, String content) {
        return new ChunkHit(id, materialId, "notes.md", title, "用户导入", heading, content, 12);
    }
}
