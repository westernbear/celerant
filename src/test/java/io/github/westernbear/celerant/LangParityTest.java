package io.github.westernbear.celerant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.junit.jupiter.api.Test;

class LangParityTest {
	@Test
	void enAndKoKeySetsMatch() throws Exception {
		Path root = Path.of("src/main/resources/assets/celerant/lang");
		Map<String, String> en = read(root.resolve("en_us.json"));
		Map<String, String> ko = read(root.resolve("ko_kr.json"));
		Set<String> enKeys = en.keySet();
		Set<String> koKeys = ko.keySet();
		assertEquals(enKeys, koKeys, "Missing in ko: "
			+ enKeys.stream().filter(k -> !koKeys.contains(k)).collect(Collectors.toSet())
			+ " Missing in en: "
			+ koKeys.stream().filter(k -> !enKeys.contains(k)).collect(Collectors.toSet()));
	}

	private static Map<String, String> read(Path path) throws Exception {
		String json = Files.readString(path, StandardCharsets.UTF_8);
		return new Gson().fromJson(json, new TypeToken<Map<String, String>>() {
		}.getType());
	}
}
