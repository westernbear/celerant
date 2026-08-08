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
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;

import java.util.EnumMap;
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

	private IrisToonPatcher() {
	}

	public static Map<PatchShaderType, String> patch(
			String name,
			Map<PatchShaderType, String> inputs,
			Parameters parameters,
			Map<PatchShaderType, String> transformed
	) {
		if (Boolean.getBoolean("celerant.testing.disableToonPatch")) {
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
		if (vertex == null || fragment == null || !vertex.contains("iris_UV1") || !fragment.contains("iris_FragData0")) {
			warnOnce(name, "required Iris entity color anchors were not found", null);
			return transformed;
		}
		if (NON_PRIMARY_FRAGMENT_OUTPUT.matcher(fragment).find()) {
			warnOnce(name, "multiple G-buffer attachments have pack-defined semantics", null);
			return transformed;
		}
		if (vertex.contains(MARKER_VARYING) || fragment.contains(MARKER_VARYING)
				|| vertex.contains(NORMAL_VARYING) || fragment.contains(NORMAL_VARYING)) {
			return transformed;
		}

		boolean hasNormalAnchors = IRIS_NORMAL.matcher(vertex).find() && IRIS_NORMAL_MATRIX.matcher(vertex).find();
		try {
			String patchedVertex = patchVertex(vertex, hasNormalAnchors);
			String patchedFragment = patchFragment(fragment, hasNormalAnchors);
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

			unit.prependMainFunctionBody(parser,
					MARKER_VARYING + " = (iris_UV1.y == 15) ? 1 : 0;");
			if (passNormal) {
				unit.prependMainFunctionBody(parser,
						NORMAL_VARYING + " = normalize(iris_NormalMat * iris_Normal);");
			}
		});
	}

	private static String patchFragment(String source, boolean receiveNormal) {
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
				unit.appendMainFunctionBody(parser, normalToonStatement());
			} else {
				unit.appendMainFunctionBody(parser, luminanceToonStatement());
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

	private static String normalToonStatement() {
		return """
				{
				    vec3 celerant_vrm_rgb = iris_FragData0.rgb;
				    vec3 celerant_vrm_n = normalize(celerant_vrm_toon_normal);
				    float celerant_vrm_light_len2 = dot(shadowLightPosition, shadowLightPosition);
				    vec3 celerant_vrm_l = celerant_vrm_light_len2 > 0.0001
				        ? shadowLightPosition * inversesqrt(celerant_vrm_light_len2)
				        : normalize(vec3(0.35, 0.80, 0.48));
				    vec3 celerant_vrm_v = vec3(0.0, 0.0, 1.0);
				    float celerant_vrm_ndl = max(dot(celerant_vrm_n, celerant_vrm_l), 0.0);
				    float celerant_vrm_ramp = celerant_vrm_ndl < 0.32 ? 0.70 : (celerant_vrm_ndl < 0.68 ? 0.84 : 0.94);
				    float celerant_vrm_nv = clamp(abs(dot(celerant_vrm_n, celerant_vrm_v)), 0.0, 1.0);
				    float celerant_vrm_fresnel = pow(1.0 - celerant_vrm_nv, 3.0);
				    float celerant_vrm_edge = smoothstep(0.10, 0.24, fwidth(celerant_vrm_ndl));
				    if (celerant_vrm_toon_marker != 0) {
				        vec3 celerant_vrm_lit = celerant_vrm_rgb * celerant_vrm_ramp;
				        vec3 celerant_vrm_headroom = max(vec3(1.0) - celerant_vrm_lit, vec3(0.0));
				        celerant_vrm_lit += min(max(celerant_vrm_rgb, vec3(0.0)) * (0.05 * celerant_vrm_fresnel),
				            celerant_vrm_headroom);
				        iris_FragData0.rgb = mix(celerant_vrm_lit, celerant_vrm_lit * 0.74, celerant_vrm_edge);
				    }
				}
				""";
	}

	private static String luminanceToonStatement() {
		return """
				{
				    vec3 celerant_vrm_rgb = iris_FragData0.rgb;
				    float celerant_vrm_luma = max(dot(max(celerant_vrm_rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722)), 0.0);
				    float celerant_vrm_log_luma = log2(1.0 + celerant_vrm_luma);
				    float celerant_vrm_quantized_luma = exp2(floor(celerant_vrm_log_luma * 4.0 + 0.5) / 4.0) - 1.0;
				    vec3 celerant_vrm_toon_rgb = celerant_vrm_luma > 0.0001
				        ? celerant_vrm_rgb * (celerant_vrm_quantized_luma / celerant_vrm_luma)
				        : celerant_vrm_rgb;
				    if (celerant_vrm_toon_marker != 0) {
				        iris_FragData0.rgb = celerant_vrm_toon_rgb;
				    }
				}
				""";
	}

	public static void main(String[] args) {
		String vertex = "#version 330 core\nin ivec2 iris_UV1;\nin vec3 iris_Normal;\nuniform mat3 iris_NormalMat;\nvoid main(){gl_Position=vec4(0.0);}";
		String fragment = "#version 330 core\nout vec4 iris_FragData0;\nvoid main(){iris_FragData0=vec4(1.0);}";
		String patchedVertex = patchVertex(vertex, true);
		String patchedFragment = patchFragment(fragment, true);
		String fallbackFragment = patchFragment(fragment, false);
		assert patchedVertex.contains(MARKER_VARYING) && patchedVertex.contains(NORMAL_VARYING)
				&& patchedFragment.contains("celerant_vrm_ramp") && patchedFragment.contains("shadowLightPosition")
				&& patchedFragment.contains("celerant_vrm_headroom") && !patchedFragment.contains("celerant_vrm_spec")
				&& fallbackFragment.contains("celerant_vrm_quantized_luma")
				&& !fallbackFragment.contains("celerant_vrm_edge_signal")
				&& NON_PRIMARY_FRAGMENT_OUTPUT.matcher("iris_FragData1 = vec4(0.0);").find()
				: "glsl-transformer toon patch self-check failed";
	}
}
