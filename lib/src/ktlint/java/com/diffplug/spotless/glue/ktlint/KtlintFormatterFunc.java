/*
 * Copyright 2021-2022 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.spotless.glue.ktlint;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.pinterest.ktlint.core.KtLint;
import com.pinterest.ktlint.core.KtLint.ExperimentalParams;
import com.pinterest.ktlint.core.LintError;
import com.pinterest.ktlint.core.RuleSet;
import com.pinterest.ktlint.core.api.DefaultEditorConfigProperties;
import com.pinterest.ktlint.core.api.EditorConfigOverride;
import com.pinterest.ktlint.core.api.UsesEditorConfigProperties;
import com.pinterest.ktlint.ruleset.experimental.ExperimentalRuleSetProvider;
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider;

import com.diffplug.spotless.FormatterFunc;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

public class KtlintFormatterFunc implements FormatterFunc.NeedsFile {

	private final List<RuleSet> rulesets;
	private final Map<String, String> userData;
	private final Function2<? super LintError, ? super Boolean, Unit> formatterCallback;
	private final boolean isScript;
	private final EditorConfigOverride editorConfigOverride;

	/**
	 * Non-empty editorConfigOverrideMap requires KtLint 0.45.2.
	 */
	public KtlintFormatterFunc(boolean isScript, boolean useExperimental, Map<String, String> userData,
			Map<String, Object> editorConfigOverrideMap) {
		rulesets = new ArrayList<>();
		rulesets.add(new StandardRuleSetProvider().get());

		if (useExperimental) {
			rulesets.add(new ExperimentalRuleSetProvider().get());
		}
		this.userData = userData;
		formatterCallback = new FormatterCallback();
		this.isScript = isScript;

		if (editorConfigOverrideMap.isEmpty()) {
			this.editorConfigOverride = null;
		} else {
			this.editorConfigOverride = createEditorConfigOverride(editorConfigOverrideMap);
		}
	}

	/**
	 * Create EditorConfigOverride from user provided parameters.
	 * Calling this method requires KtLint 0.45.2.
	 */
	private EditorConfigOverride createEditorConfigOverride(Map<String, Object> editorConfigOverrideMap) {
		// Get properties from rules in the rule sets
		Stream<UsesEditorConfigProperties.EditorConfigProperty<?>> ruleProperties = rulesets.stream()
				.flatMap(ruleSet -> Arrays.stream(ruleSet.getRules()))
				.filter(rule -> rule instanceof UsesEditorConfigProperties)
				.flatMap(rule -> ((UsesEditorConfigProperties) rule).getEditorConfigProperties().stream());

		// Create a mapping of properties to their names based on rule properties and default properties
		Map<String, UsesEditorConfigProperties.EditorConfigProperty<?>> supportedProperties = Stream
				.concat(ruleProperties, DefaultEditorConfigProperties.INSTANCE.getDefaultEditorConfigProperties().stream())
				.distinct()
				.collect(Collectors.toMap(property -> property.getType().getName(), property -> property));

		// Create config properties based on provided property names and values
		@SuppressWarnings("unchecked")
		Pair<UsesEditorConfigProperties.EditorConfigProperty<?>, ?>[] properties = editorConfigOverrideMap.entrySet().stream()
				.map(entry -> {
					UsesEditorConfigProperties.EditorConfigProperty<?> property = supportedProperties.get(entry.getKey());
					if (property != null) {
						return new Pair<>(property, entry.getValue());
					} else {
						return null;
					}
				})
				.filter(Objects::nonNull)
				.toArray(Pair[]::new);

		return EditorConfigOverride.Companion.from(properties);
	}

	static class FormatterCallback implements Function2<LintError, Boolean, Unit> {
		@Override
		public Unit invoke(LintError lint, Boolean corrected) {
			if (!corrected) {
				throw new AssertionError("Error on line: " + lint.getLine() + ", column: " + lint.getCol() + "\n" + lint.getDetail());
			}
			return null;
		}
	}

	@Override
	public String applyWithFile(String unix, File file) throws Exception {

		if (editorConfigOverride != null) {
			// Use ExperimentalParams with EditorConfigOverride which requires KtLint 0.45.2
			return KtLint.INSTANCE.format(new ExperimentalParams(
					file.getName(),
					unix,
					rulesets,
					userData,
					formatterCallback,
					isScript,
					null,
					false,
					editorConfigOverride,
					false));
		} else {
			// Use Params for backward compatibility
			return KtLint.INSTANCE.format(new KtLint.Params(
					file.getName(),
					unix,
					rulesets,
					userData,
					formatterCallback,
					isScript,
					null,
					false));
		}
	}
}
