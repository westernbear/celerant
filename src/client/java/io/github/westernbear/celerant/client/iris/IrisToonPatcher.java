package io.github.westernbear.celerant.client.iris;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.token_filter.ChannelFilter;
import io.github.douira.glsl_transformer.token_filter.TokenChannel;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

public final class IrisToonPatcher {
	private static final System.Logger LOGGER = System.getLogger(IrisToonPatcher.class.getName());
	private static final Set<String> WARNED_PROGRAMS = ConcurrentHashMap.newKeySet();
	private static final String MARKER_VARYING = "celerant_vrm_toon_marker";
	private static final String NORMAL_VARYING = "celerant_vrm_toon_normal";
	private static final Pattern IRIS_NORMAL = Pattern.compile("\\biris_Normal\\b");
	private static final Pattern IRIS_NORMAL_MATRIX = Pattern.compile("\\biris_NormalMat\\b");
	private static final Pattern SHADOW_LIGHT_POSITION = Pattern.compile("\\bshadowLightPosition\\b");
	private static final Pattern NON_PRIMARY_FRAGMENT_OUTPUT = Pattern.compile("\\biris_FragData[1-9][0-9]*\\b");
	private static final Pattern FLOAT_OUTPUT = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+vec4\\s+iris_FragData%d\\b");
	private static final Pattern F16_OUTPUT = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+f16vec4\\s+iris_FragData0\\b");
	private static final Pattern UINT_OUTPUT = Pattern.compile("(?:layout\\s*\\([^)]*\\)\\s*)?out\\s+uvec4\\s+iris_FragData0\\b");
	private static volatile boolean enabled = true;
	private static volatile String cachedPackPath = "";
	private static volatile long cachedPackSize = -1L;
	private static volatile long cachedPackModified = -1L;
	private static volatile PackProfile cachedPackProfile = PackProfile.NONE;

	private enum PackProfile {
		NONE, DIRECT, DUPLICATE, BLISS, MELLOW, NOBLE, F16
	}

	private enum OutputCodec {
		UNSUPPORTED, DIRECT0, DIRECT1, DIRECT_DUPLICATE, F16_DIRECT0, BLISS_PAIR8, MELLOW_COLOR, NOBLE_UNORM_Z
	}

	private IrisToonPatcher() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean enabled) {
		IrisToonPatcher.enabled = enabled;
	}

	public static Map<PatchShaderType, String> patch(
			String name,
			Map<PatchShaderType, String> inputs,
			Parameters parameters,
			Map<PatchShaderType, String> transformed
	) {
		if (!enabled || Boolean.getBoolean("celerant.testing.disableToonPatch")) {
			return transformed;
		}
		if (!isEntityProgram(name) || parameters == null || parameters.patch != Patch.VANILLA || transformed == null) {
			return transformed;
		}
		if (hasIntermediateStage(inputs) || hasIntermediateStage(transformed)) {
			warnOnce(name, "geometry/tessellation stages are not safe for the VRM marker pass", null);
			return transformed;
		}

		String vertex = transformed.get(PatchShaderType.VERTEX);
		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		if (vertex == null || fragment == null || !vertex.contains("iris_UV1")) {
			warnOnce(name, "required Iris entity color anchors were not found", null);
			return transformed;
		}
		if (vertex.contains(MARKER_VARYING) || fragment.contains(MARKER_VARYING)
				|| vertex.contains(NORMAL_VARYING) || fragment.contains(NORMAL_VARYING)) {
			return transformed;
		}

		OutputCodec codec = outputCodec(fragment, currentPackProfile());
		if (codec == OutputCodec.UNSUPPORTED) {
			String reason = NON_PRIMARY_FRAGMENT_OUTPUT.matcher(fragment).find()
				? "multiple G-buffer attachments have pack-defined semantics"
				: "the primary entity output is not an audited floating-point color target";
			warnOnce(name, reason, null);
			return transformed;
		}
		boolean hasNormalAnchors = IRIS_NORMAL.matcher(vertex).find() && IRIS_NORMAL_MATRIX.matcher(vertex).find();
		try {
			String patchedVertex = patchVertex(vertex, hasNormalAnchors);
			String patchedFragment = patchFragment(fragment, hasNormalAnchors, codec);
			EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
			result.putAll(transformed);
			result.put(PatchShaderType.VERTEX, patchedVertex);
			result.put(PatchShaderType.FRAGMENT, patchedFragment);
			return result;
		} catch (RuntimeException | LinkageError | AssertionError exception) {
			warnOnce(name, "the GLSL AST edit failed", exception);
			return transformed;
		}
	}

	private static OutputCodec outputCodec(String fragment, PackProfile profile) {
		boolean multiple = NON_PRIMARY_FRAGMENT_OUTPUT.matcher(fragment).find();
		return switch (profile) {
			case DIRECT -> hasFloatOutput(fragment, 0) ? OutputCodec.DIRECT0 : OutputCodec.UNSUPPORTED;
			case DUPLICATE -> hasFloatOutput(fragment, 0) && hasFloatOutput(fragment, 1)
				? OutputCodec.DIRECT_DUPLICATE : OutputCodec.UNSUPPORTED;
			case F16 -> F16_OUTPUT.matcher(fragment).find() ? OutputCodec.F16_DIRECT0 : OutputCodec.UNSUPPORTED;
			case BLISS -> hasFloatOutput(fragment, 0)
				? (fragment.contains("encodeVec2") && !fragment.contains("GBUFFERS_ENTITIES_TRANSLUCENT")
					&& !fragment.contains("VANILLA_EMISSIVES") ? OutputCodec.BLISS_PAIR8 : OutputCodec.DIRECT0)
				: OutputCodec.UNSUPPORTED;
			case MELLOW -> fragment.contains("out vec4 Color") ? OutputCodec.MELLOW_COLOR : OutputCodec.UNSUPPORTED;
			case NOBLE -> UINT_OUTPUT.matcher(fragment).find() ? OutputCodec.NOBLE_UNORM_Z
				: hasFloatOutput(fragment, 1) ? OutputCodec.DIRECT1 : OutputCodec.UNSUPPORTED;
			case NONE -> !multiple && hasFloatOutput(fragment, 0) ? OutputCodec.DIRECT0 : OutputCodec.UNSUPPORTED;
		};
	}

	private static boolean hasFloatOutput(String fragment, int location) {
		return Pattern.compile(FLOAT_OUTPUT.pattern().formatted(location)).matcher(fragment).find();
	}

	private static synchronized PackProfile currentPackProfile() {
		try {
			String name = Iris.getIrisConfig().getShaderPackName().orElse("");
			Path directory = Iris.getShaderpacksDirectory().toAbsolutePath().normalize();
			Path pack = directory.resolve(name).normalize();
			if (!pack.startsWith(directory) || !Files.isRegularFile(pack)) {
				return PackProfile.NONE;
			}
			long size = Files.size(pack);
			long modified = Files.getLastModifiedTime(pack).toMillis();
			String path = pack.toString();
			if (path.equals(cachedPackPath) && size == cachedPackSize && modified == cachedPackModified) {
				return cachedPackProfile;
			}
			cachedPackPath = path;
			cachedPackSize = size;
			cachedPackModified = modified;
			cachedPackProfile = packProfile(sha512(pack));
			return cachedPackProfile;
		} catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
			return PackProfile.NONE;
		}
	}

	private static String sha512(Path path) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		try (var input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) >= 0;) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static PackProfile packProfile(String hash) {
		return switch (hash) {
			case "6bd95215755d25812556ce790d976221f7d677d63112e3e4d3e70b08a62ed41348fa3792dd31bbe720d1e46fe2d525cadb4f66e6358118e1f4aa8e0d11f25c39",
				"9098dd9e0c18b80f7aba2839cea33ce9a614d97665bbfcac87ccce6e4771667c41602d99088852cb1642ccab20b2ceff9b98af8f2e795bd0d3b90b7c9cbab914",
				"3e68c8038e38b0860cf1258e5fc84bcd98007a70fb7ba49e306186be6e905187527b630b77cb9e8cc8ee80865606c0fb398c419a22f0076b2ed5c7d73748a9ba",
				"6c81aa2acafbb8585fda3e3b3d87446552003537460c5f319a673210247ca7854f33fcd88df083413153bd4b84e4e86e5d6f3d32b4963c1f4fd18379cb1f7efe",
				"171131e16b65af67c3ed2fe75f3bde9d81f66b4e921ba3ca33187cff7c87b40e1f21637e95e2fa3a1567b0717374809b18adc240c5ef74622c8e9317ecc14f9d",
				"bc2d9f3d135e8608f4735e5372b893e17cdc630b7536ec1e0b9a4d2734167d1a2102e60de60731d21d8a9741cec1a82530d03bf634b7e7cf9a0f107077c0e7fa",
				"17196b03c638bcefa7a4ea7373245969472809adca3722323d2abd0b43cff834c65c80377a1c18b24e89fdf0b7e1c9f3eb1d022fb4f18e5c5f04107ea56d606c",
				"bb0f2608f9055cf992c91375c98e77feafef4f596d9bf4046683664e6eb49d9cf294b3d74b28e640c4301b3bb525dca41c2ea97b992f14cbf923dbef3f5a0b60",
				"d1431cb0d3b914de008710f63f6c100802fa4a08460f73b2b72f9ada8fb03cca7c6f42b6e213568ec233e4cdfc5dcd7548cc9b44ba444d2ade47464d15f2a0f0",
				"bf536e5cc61260c9c6fb65175ecf121b2b055b783bd3cde98842538dddab9b7d43ed29b1f80e5343b5eaaca63cc843c90dbad5d7b0142fa9144631dee55d95a1",
				"0aa79d97d3d72e8963bb9acedc8308ef33cc9c41c28e7dbcfcf8eee53fa73c509033a2c05deaa3c0890c5abefd45b65ef938ff3417acd51d5471fae3f21ab9a9",
				"09b7d5e86594ea15197e9dea218a8c6b1d5b057a83293a786a4ed0f382bb42fb25e6f505228b5eb9b1eaf7b90699c0c0185c49377b1970772d158e10e5f59eb0",
				"c290ead23a7c549fec821761f7f00b5257cdc466584d7bff7e375c7fbafba33638a5aed71f97ad0cf09bd4226925ebc68a4c5b77a020af336856d5876b422c51",
				"75483799561fef131c57cec71c3bce499e7a2536d7a77f708cb36046cfbc582e278edbf095c53b47f1c2f1f9d90bac02d0ef85c4aaa06bbc2432f7343f28b2bc" -> PackProfile.DIRECT;
			case "ed952ab1ebb83de056d93d6cc484a29d369fc47764ee8738a81135d01c81c63e40990cc662bf7d2d60da9170601f8c12d748a09e0874dbbefa0d876d61b809d6" -> PackProfile.MELLOW;
			case "67d3938a1ad27ee0951b63703cd638b9db36bcf92c66dfe7ffff4c01ac365e952bbf630af1bb5bbddde8a723e7fb287e88bf80fe0301c70f12b4ebe8546c7947",
				"bb81f4e7742407298a5d807245cd88b7ee3f15c5b790abdaa570e8228fabfd1a4addf38fcada4df0d23f24d372ca0a0b540ccf4de1189fc8561bf6e746b4af88" -> PackProfile.DUPLICATE;
			case "dafc60be4980ec40f40edc0f2625cb0976f3c9ce5ed86383146a120480826bb1de70ef5e38b7f1437294ed4d38c6ef3c82ebef0ae4e00b8cee165788c9c18280" -> PackProfile.BLISS;
			case "c177982e8fda4b64317725af1d3d9487c2a6a0eef470885d551669fc2fd3b0974ac3f5068ccd8ae8f111b03f269ec7bc7213ad00607af369f156f34d08a6eb5c" -> PackProfile.NOBLE;
			case "e8f4f37c35bf54d8562a4d29771922b4bfce6fd4f5a25f38b4d6de58ee19907f73d12b980fc137dc2cb3b2b705264dfa8b2e441efaca8649a6a6d0f00cf5589b" -> PackProfile.F16;
			default -> PackProfile.NONE;
		};
	}

	private static void warnOnce(String name, String reason, Throwable exception) {
		if (!WARNED_PROGRAMS.add(name)) {
			return;
		}
		String message = "Could not safely apply the in-memory VRM toon patch to Iris program '" + name
			+ "' because " + reason + "; using the shader pack source unchanged.";
		if (exception == null) {
			LOGGER.log(System.Logger.Level.WARNING, message);
		} else {
			LOGGER.log(System.Logger.Level.WARNING, message, exception);
		}
	}

	private static boolean isEntityProgram(String name) {
		return name != null && name.startsWith("entities_") && !name.startsWith("shadow_");
	}

	private static boolean hasIntermediateStage(Map<PatchShaderType, String> sources) {
		return sources != null && (sources.get(PatchShaderType.GEOMETRY) != null
				|| sources.get(PatchShaderType.TESS_CONTROL) != null
				|| sources.get(PatchShaderType.TESS_EVAL) != null);
	}

	private static String patchVertex(String source, boolean passNormal) {
		return transform(source, (parser, unit) -> {
			unit.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"flat out int " + MARKER_VARYING + ";");
			if (passNormal) {
				unit.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
						"smooth out vec3 " + NORMAL_VARYING + ";");
			}

			if (passNormal) {
				unit.prependMainFunctionBody(parser, """
						{
						    %s = (iris_UV1.y == 15) ? 1 : 0;
						    %s = vec3(0.0);
						    if (%s != 0) {
						        %s = normalize(iris_NormalMat * iris_Normal);
						    }
						}
						""".formatted(MARKER_VARYING, NORMAL_VARYING, MARKER_VARYING, NORMAL_VARYING));
			} else {
				unit.prependMainFunctionBody(parser,
						MARKER_VARYING + " = (iris_UV1.y == 15) ? 1 : 0;");
			}
		});
	}

	private static String patchFragment(String source, boolean receiveNormal, OutputCodec codec) {
		boolean declareShadowLight = receiveNormal && !SHADOW_LIGHT_POSITION.matcher(source).find();
		return transform(source, (parser, unit) -> {
			unit.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"flat in int " + MARKER_VARYING + ";");
			if (receiveNormal) {
				if (declareShadowLight) {
					unit.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
							"uniform vec3 shadowLightPosition;");
				}
				unit.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
						"smooth in vec3 " + NORMAL_VARYING + ";");
				unit.appendMainFunctionBody(parser, normalToonStatement(codec));
			} else {
				unit.appendMainFunctionBody(parser, luminanceToonStatement(codec));
			}
		});
	}

	private static String transform(String source, BiConsumer<ASTParser, TranslationUnit> edit) {
		SingleASTTransformer<JobParameters> transformer = new SingleASTTransformer<>();
		transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
		transformer.setParsingCacheStrategy(ASTParser.ParsingCacheStrategy.TWO_TIER);
		transformer.setTokenFilter(new ChannelFilter<>(TokenChannel.PREPROCESSOR));
		transformer.setPrintType(PrintType.SIMPLE);
		transformer.setTransformation(unit -> {
			boolean hasMain = unit.getOneFunctionDefinitionBodyOptional("main").isPresent();
			assert hasMain : "Iris shader must contain exactly one main function";
			if (!hasMain) {
				throw new IllegalArgumentException("Iris shader has no unique main function");
			}
			edit.accept(transformer, unit);
		});
		return transformer.transform(source);
	}

	private static String normalToonStatement(OutputCodec codec) {
		ToonTarget target = toonTarget(codec);
		return """
				{
				    if (celerant_vrm_toon_marker != 0) {
				        %s
				        vec3 celerant_vrm_rgb = %s;
				        vec3 celerant_vrm_n = normalize(celerant_vrm_toon_normal);
				        float celerant_vrm_light_len2 = dot(shadowLightPosition, shadowLightPosition);
				        vec3 celerant_vrm_l = celerant_vrm_light_len2 > 0.0001
				            ? shadowLightPosition * inversesqrt(celerant_vrm_light_len2)
				            : normalize(vec3(0.35, 0.80, 0.48));
				        float celerant_vrm_ndl = max(dot(celerant_vrm_n, celerant_vrm_l), 0.0);
				        float celerant_vrm_ramp = smoothstep(0.20, 0.70, celerant_vrm_ndl);
				        vec3 celerant_vrm_shade = celerant_vrm_rgb * 0.72;
				        vec3 celerant_vrm_lit = celerant_vrm_rgb * 0.96;
				        vec3 celerant_vrm_result = mix(celerant_vrm_shade, celerant_vrm_lit, celerant_vrm_ramp);
				        %s
				    }
				}
				""".formatted(target.prelude(), target.rgb(), target.write());
	}

	private static String luminanceToonStatement(OutputCodec codec) {
		ToonTarget target = toonTarget(codec);
		return """
				{
				    if (celerant_vrm_toon_marker != 0) {
				        %s
				        vec3 celerant_vrm_rgb = %s;
				        float celerant_vrm_luma = max(dot(max(celerant_vrm_rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722)), 0.0);
				        float celerant_vrm_log_luma = log2(1.0 + celerant_vrm_luma);
				        float celerant_vrm_quantized_luma = exp2(floor(celerant_vrm_log_luma * 4.0 + 0.5) / 4.0) - 1.0;
				        vec3 celerant_vrm_toon_rgb = celerant_vrm_luma > 0.0001
				            ? celerant_vrm_rgb * (celerant_vrm_quantized_luma / celerant_vrm_luma)
				            : celerant_vrm_rgb;
				        vec3 celerant_vrm_result = celerant_vrm_toon_rgb;
				        %s
				    }
				}
				""".formatted(target.prelude(), target.rgb(), target.write());
	}

	private static ToonTarget toonTarget(OutputCodec codec) {
		return switch (codec) {
			case DIRECT0 -> new ToonTarget("", "iris_FragData0.rgb", "iris_FragData0.rgb = celerant_vrm_result;");
			case DIRECT1 -> new ToonTarget("", "iris_FragData1.rgb", "iris_FragData1.rgb = celerant_vrm_result;");
			case DIRECT_DUPLICATE -> new ToonTarget("", "iris_FragData0.rgb", """
					iris_FragData0.rgb = celerant_vrm_result;
					iris_FragData1.rgb = celerant_vrm_result;
					""");
			case F16_DIRECT0 -> new ToonTarget("", "vec3(iris_FragData0.rgb)",
				"iris_FragData0.rgb = f16vec3(celerant_vrm_result);");
			case BLISS_PAIR8 -> new ToonTarget("""
					vec2 celerant_vrm_pair_r = fract(iris_FragData0.r * (65535.0 / vec2(256.0, 65536.0))) * (256.0 / 255.0);
					vec2 celerant_vrm_pair_g = fract(iris_FragData0.g * (65535.0 / vec2(256.0, 65536.0))) * (256.0 / 255.0);
					vec2 celerant_vrm_pair_b = fract(iris_FragData0.b * (65535.0 / vec2(256.0, 65536.0))) * (256.0 / 255.0);
					""", "vec3(celerant_vrm_pair_r.x, celerant_vrm_pair_g.x, celerant_vrm_pair_b.x)", """
					iris_FragData0.r = dot(floor(vec2(celerant_vrm_result.r, celerant_vrm_pair_r.y) * 255.0), vec2(1.0, 256.0)) / 65535.0;
					iris_FragData0.g = dot(floor(vec2(celerant_vrm_result.g, celerant_vrm_pair_g.y) * 255.0), vec2(1.0, 256.0)) / 65535.0;
					iris_FragData0.b = dot(floor(vec2(celerant_vrm_result.b, celerant_vrm_pair_b.y) * 255.0), vec2(1.0, 256.0)) / 65535.0;
					""");
			case MELLOW_COLOR -> new ToonTarget("", "Color.rgb", "Color.rgb = celerant_vrm_result;");
			case NOBLE_UNORM_Z -> new ToonTarget("vec4 celerant_vrm_packed = unpackUnorm4x8(iris_FragData0.z);",
				"celerant_vrm_packed.rgb", """
					celerant_vrm_packed.rgb = celerant_vrm_result;
					iris_FragData0.z = packUnorm4x8(celerant_vrm_packed);
					""");
			case UNSUPPORTED -> throw new IllegalArgumentException("unsupported VRM toon output codec");
		};
	}

	private record ToonTarget(String prelude, String rgb, String write) {
	}

	public static void main(String[] args) {
		String vertex = "#version 330 core\nin ivec2 iris_UV1;\nin vec3 iris_Normal;\nuniform mat3 iris_NormalMat;\nvoid main(){gl_Position=vec4(0.0);}";
		String fragment = "#version 330 core\nout vec4 iris_FragData0;\nvoid main(){iris_FragData0=vec4(1.0);}";
		String patchedVertex = patchVertex(vertex, true);
		String patchedFragment = patchFragment(fragment, true, OutputCodec.DIRECT0);
		String fallbackFragment = patchFragment(fragment, false, OutputCodec.DIRECT0);
		int vertexGuard = patchedVertex.indexOf("if (" + MARKER_VARYING + " != 0)");
		int fragmentGuard = patchedFragment.indexOf("if (" + MARKER_VARYING + " != 0)");
		int fallbackGuard = fallbackFragment.indexOf("if (" + MARKER_VARYING + " != 0)");
		assert patchedVertex.contains(MARKER_VARYING) && patchedVertex.contains(NORMAL_VARYING)
				&& vertexGuard >= 0 && vertexGuard < patchedVertex.indexOf("normalize(iris_NormalMat * iris_Normal)")
				&& patchedFragment.contains("celerant_vrm_ramp") && patchedFragment.contains("shadowLightPosition")
				&& fragmentGuard >= 0 && fragmentGuard < patchedFragment.indexOf("normalize(" + NORMAL_VARYING + ")")
				&& patchedFragment.contains("celerant_vrm_shade") && !patchedFragment.contains("fwidth(")
				&& fallbackFragment.contains("celerant_vrm_quantized_luma")
				&& fallbackGuard >= 0 && fallbackGuard < fallbackFragment.indexOf("log2(")
				&& !fallbackFragment.contains("celerant_vrm_edge_signal")
				&& hasFloatOutput("layout(location = 0) out vec4 iris_FragData0;", 0)
				&& UINT_OUTPUT.matcher("layout(location = 0) out uvec4 iris_FragData0;").find()
				&& toonTarget(OutputCodec.MELLOW_COLOR).rgb().equals("Color.rgb")
				&& NON_PRIMARY_FRAGMENT_OUTPUT.matcher("iris_FragData1 = vec4(0.0);").find()
				: "glsl-transformer toon patch self-check failed";
	}
}
