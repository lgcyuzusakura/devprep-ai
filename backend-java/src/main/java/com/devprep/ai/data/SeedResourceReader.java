package com.devprep.ai.data;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class SeedResourceReader {
    private final ResourceLoader loader;
    public SeedResourceReader(ResourceLoader loader) { this.loader = loader; }

    public String read(String location, String defaultLocation) throws IOException {
        String resolved = location == null || location.isBlank() ? defaultLocation : location;
        byte[] bytes;
        if (resolved.startsWith("classpath:") || resolved.startsWith("file:")) {
            try (InputStream input = loader.getResource(resolved).getInputStream()) { bytes = input.readAllBytes(); }
        } else bytes = Files.readAllBytes(Path.of(resolved));
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
