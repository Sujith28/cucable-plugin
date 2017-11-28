/*
 * Copyright 2017 trivago N.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.trivago.rta.gherkin;

import com.trivago.rta.exceptions.CucablePluginException;
import com.trivago.rta.vo.SingleScenario;
import com.trivago.rta.vo.Step;
import gherkin.AstBuilder;
import gherkin.Parser;
import gherkin.ParserException;
import gherkin.ast.Background;
import gherkin.ast.Examples;
import gherkin.ast.Feature;
import gherkin.ast.GherkinDocument;
import gherkin.ast.Scenario;
import gherkin.ast.ScenarioDefinition;
import gherkin.ast.ScenarioOutline;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Singleton
public class GherkinDocumentParser {

    private final GherkinToCucableConverter gherkinToCucableConverter;

    @Inject
    public GherkinDocumentParser(final GherkinToCucableConverter gherkinToCucableConverter) {
        this.gherkinToCucableConverter = gherkinToCucableConverter;
    }

    /**
     * Returns a {@link com.trivago.rta.vo.SingleScenario} list from a given feature file.
     *
     * @param featureContent      a feature string.
     * @param scenarioLineNumber  an optional line number of a scenario inside a feature file.
     * @param includeScenarioTags optional scenario tags to include into scenario generation.
     * @param excludeScenarioTags optional scenario tags to exclude from scenario generation.
     * @return a {@link com.trivago.rta.vo.SingleScenario} list.
     * @throws CucablePluginException see {@link CucablePluginException}.
     */
    public List<SingleScenario> getSingleScenariosFromFeature(
            final String featureContent,
            final Integer scenarioLineNumber,
            final List<String> includeScenarioTags,
            final List<String> excludeScenarioTags) throws CucablePluginException {

        GherkinDocument gherkinDocument = getGherkinDocumentFromFeatureFileContent(featureContent);

        Feature feature = gherkinDocument.getFeature();
        String featureName = feature.getName();
        List<String> featureTags =
                gherkinToCucableConverter.convertGherkinTagsToCucableTags(feature.getTags());

        ArrayList<SingleScenario> singleScenarioFeatures = new ArrayList<>();
        List<Step> backgroundSteps = new ArrayList<>();

        List<ScenarioDefinition> scenarioDefinitions = feature.getChildren();
        for (ScenarioDefinition scenarioDefinition : scenarioDefinitions) {
            String scenarioName = scenarioDefinition.getName();

            if (scenarioDefinition instanceof Background) {
                // Save background steps in order to add them to every scenario inside the same feature
                Background background = (Background) scenarioDefinition;
                backgroundSteps = gherkinToCucableConverter.convertGherkinStepsToCucableSteps(background.getSteps());
                continue;
            }

            if (scenarioDefinition instanceof Scenario) {
                Scenario scenario = (Scenario) scenarioDefinition;
                if (scenarioLineNumber == null || scenario.getLocation().getLine() == scenarioLineNumber) {
                    SingleScenario singleScenario =
                            new SingleScenario(featureName, scenarioName, featureTags, backgroundSteps);
                    addGherkinScenarioInformationToSingleScenario(scenario, singleScenario);

                    if (scenarioShouldBeIncluded(singleScenario.getScenarioTags(), includeScenarioTags, excludeScenarioTags)) {
                        singleScenarioFeatures.add(singleScenario);
                    }
                }
                continue;
            }

            if (scenarioDefinition instanceof ScenarioOutline) {
                ScenarioOutline scenarioOutline = (ScenarioOutline) scenarioDefinition;
                if (scenarioLineNumber == null || scenarioOutline.getLocation().getLine() == scenarioLineNumber) {
                    List<SingleScenario> outlineScenarios =
                            getSingleScenariosFromOutline(
                                    scenarioOutline,
                                    featureName,
                                    featureTags,
                                    backgroundSteps,
                                    includeScenarioTags,
                                    excludeScenarioTags);
                    singleScenarioFeatures.addAll(outlineScenarios);
                }
            }
        }
        return singleScenarioFeatures;
    }

    /**
     * Returns a list of Cucable single scenarios from a Gherkin scenario outline.
     *
     * @param scenarioOutline     a Gherkin {@link ScenarioOutline}.
     * @param featureName         The name of the feature this scenario outline belongs to.
     * @param featureTags         The feature tags of the parent feature.
     * @param backgroundSteps     return a Cucable {@link SingleScenario} list.
     * @param includeScenarioTags optional scenario tags to include in scenario generation.
     * @param excludeScenarioTags optional scenario tags to exclude from scenario generation.
     * @throws CucablePluginException thrown when the scenario outline does not contain an example table.
     */
    private List<SingleScenario> getSingleScenariosFromOutline(
            final ScenarioOutline scenarioOutline,
            final String featureName,
            final List<String> featureTags,
            final List<Step> backgroundSteps,
            final List<String> includeScenarioTags,
            final List<String> excludeScenarioTags) throws CucablePluginException {

        String scenarioName = scenarioOutline.getName();
        List<String> scenarioTags =
                gherkinToCucableConverter.convertGherkinTagsToCucableTags(scenarioOutline.getTags());

        if (!scenarioShouldBeIncluded(scenarioTags, includeScenarioTags, excludeScenarioTags)) {
            return Collections.emptyList();
        }

        List<SingleScenario> outlineScenarios = new ArrayList<>();

        List<Step> steps = gherkinToCucableConverter.convertGherkinStepsToCucableSteps(scenarioOutline.getSteps());

        if (scenarioOutline.getExamples().isEmpty()) {
            throw new CucablePluginException("Scenario outline without examples table!");
        }

        Examples exampleTable = scenarioOutline.getExamples().get(0);
        Map<String, List<String>> exampleMap =
                gherkinToCucableConverter.convertGherkinExampleTableToCucableExampleMap(exampleTable);

        String firstColumnHeader = (String) exampleMap.keySet().toArray()[0];
        int rowCount = exampleMap.get(firstColumnHeader).size();

        // for each example row, create a new single scenario
        for (int i = 0; i < rowCount; i++) {
            SingleScenario singleScenario =
                    new SingleScenario(featureName, scenarioName, featureTags, backgroundSteps);

            List<Step> substitutedSteps = substituteStepExamplePlaceholders(steps, exampleMap, i);
            singleScenario.setSteps(substitutedSteps);
            singleScenario.setScenarioTags(scenarioTags);
            outlineScenarios.add(singleScenario);
        }

        return outlineScenarios;
    }

    /**
     * Replaces the example value placeholders in steps by the actual example table values.
     *
     * @param steps      The Cucable {@link Step} list.
     * @param exampleMap The generated example map from an example table.
     * @param rowIndex   The row index of the example table to consider.
     * @return a {@link Step} list with substituted names.
     */
    private List<Step> substituteStepExamplePlaceholders(
            final List<Step> steps, final Map<String, List<String>> exampleMap, final int rowIndex) {

        List<Step> substitutedSteps = new ArrayList<>();
        for (Step step : steps) {
            String stepName = step.getName();
            for (String columnName : exampleMap.keySet()) {
                String columnValue = exampleMap.get(columnName).get(rowIndex);
                stepName = stepName.replace(columnName, columnValue);
            }
            substitutedSteps.add(new Step(stepName, step.getDataTable()));
        }
        return substitutedSteps;
    }

    /**
     * Adds tags and steps from a Gherkin scenario to an existing single scenario.
     *
     * @param gherkinScenario a Gherkin {@link Scenario}.
     * @param singleScenario  an existing Cucable {@link SingleScenario}.
     */
    private void addGherkinScenarioInformationToSingleScenario(
            final Scenario gherkinScenario, final SingleScenario singleScenario) {

        List<String> tags = gherkinToCucableConverter.convertGherkinTagsToCucableTags(gherkinScenario.getTags());
        singleScenario.setScenarioTags(tags);

        List<Step> steps = gherkinToCucableConverter.convertGherkinStepsToCucableSteps(gherkinScenario.getSteps());
        singleScenario.setSteps(steps);
    }

    /**
     * Get a {@link GherkinDocument} from a feature file for further processing.
     *
     * @param featureContent a feature string.
     * @return a {@link GherkinDocument}.
     * @throws CucablePluginException see {@link CucablePluginException}.
     */
    private GherkinDocument getGherkinDocumentFromFeatureFileContent(final String featureContent)
            throws CucablePluginException {

        Parser<GherkinDocument> gherkinDocumentParser = new Parser<>(new AstBuilder());
        GherkinDocument gherkinDocument;

        try {
            gherkinDocument = gherkinDocumentParser.parse(featureContent);
        } catch (ParserException parserException) {
            throw new CucablePluginException("Could not parse feature!");
        }

        if (gherkinDocument == null || gherkinDocument.getFeature() == null) {
            throw new CucablePluginException("Could not parse feature!");
        }

        return gherkinDocument;
    }

    /**
     * Checks if a scenario should be included in the runner and feature generation based on the tag settings.
     *
     * @param scenarioTags        the source tag list.
     * @param includeScenarioTags the include tags list.
     * @param excludeScenarioTags the exclude tags list.
     * @return true if an include tag  and no exclude tags are included in the source tag list.
     */
    private boolean scenarioShouldBeIncluded(
            final List<String> scenarioTags, final List<String> includeScenarioTags, final List<String> excludeScenarioTags) {

        System.out.println("*****************************************************");
        System.out.println("scenarioTags:        " + scenarioTags);
        System.out.println("includeScenarioTags: " + includeScenarioTags);
        System.out.println("excludeScenarioTags: " + excludeScenarioTags);

        // If there are no scenario tags but include scenario tags, this scenario cannot be included.
        // If there are no scenario tags and no include scenario tags, this scenario can be directly included.
        if (scenarioTags == null || scenarioTags.isEmpty()) {
            return includeScenarioTags == null || includeScenarioTags.isEmpty();
        } else {
            boolean result = false;
            for (String scenarioTag : scenarioTags) {
                if (includeScenarioTags != null && !includeScenarioTags.isEmpty()) {
                    // If there are include scenario tags, check if any scenario tag matches any include tag...
                    for (String includeScenarioTag : includeScenarioTags) {
                        if (scenarioTag.equalsIgnoreCase(includeScenarioTag)) {
                            result = true;
                            break;
                        }
                    }
                } else {
                    // ...else include all.
                    result = true;
                }

                // If there are exclude scenario tags, check if any scenario tag matches any exclude tag.
                if (excludeScenarioTags != null && !excludeScenarioTags.isEmpty()) {
                    for (String excludeScenarioTag : excludeScenarioTags) {
                        if (scenarioTag.equalsIgnoreCase(excludeScenarioTag)) {
                            return false;
                        }
                    }
                }
            }
            return result;
        }
    }
}

