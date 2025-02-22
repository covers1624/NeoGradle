package net.neoforged.gradle.common.runtime.extensions;

import com.google.common.collect.Maps;
import net.neoforged.gradle.common.runtime.definition.CommonRuntimeDefinition;
import net.neoforged.gradle.common.runtime.definition.IDelegatingRuntimeDefinition;
import net.neoforged.gradle.common.runtime.specification.CommonRuntimeSpecification;
import net.neoforged.gradle.common.runtime.tasks.DownloadAssets;
import net.neoforged.gradle.common.runtime.tasks.ExtractNatives;
import net.neoforged.gradle.common.util.VersionJson;
import net.neoforged.gradle.dsl.common.extensions.MinecraftArtifactCache;
import net.neoforged.gradle.dsl.common.extensions.repository.Repository;
import net.neoforged.gradle.dsl.common.runtime.extensions.CommonRuntimes;
import net.neoforged.gradle.dsl.common.runtime.spec.Specification;
import net.neoforged.gradle.dsl.common.runtime.tasks.Runtime;
import net.neoforged.gradle.dsl.common.runtime.tasks.tree.TaskCustomizer;
import net.neoforged.gradle.dsl.common.tasks.WithOutput;
import net.neoforged.gradle.dsl.common.util.CacheableMinecraftVersion;
import net.neoforged.gradle.dsl.common.util.CommonRuntimeUtils;
import net.neoforged.gradle.dsl.common.util.GameArtifact;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class CommonRuntimeExtension<S extends CommonRuntimeSpecification, B extends CommonRuntimeSpecification.Builder<S, B>, D extends CommonRuntimeDefinition<S>> implements CommonRuntimes<S, B, D> {
    protected final Map<String, D> definitions = Maps.newHashMap();
    protected final Map<String, Dependency> dependencies = Maps.newHashMap();
    private final Project project;

    protected CommonRuntimeExtension(Project project) {
        this.project = project;
        this.project.getExtensions().getByType(RuntimesExtension.class).add(this);
    }

    public static void configureCommonRuntimeTaskParameters(Runtime runtimeTask, Map<String, String> symbolicDataSources, String step, Specification spec, File runtimeDirectory) {
        runtimeTask.getSymbolicDataSources().set(symbolicDataSources);
        runtimeTask.getStepName().set(step);
        runtimeTask.getDistribution().set(spec.getDistribution());
        runtimeTask.getMinecraftVersion().set(CacheableMinecraftVersion.from(spec.getMinecraftVersion(), spec.getProject()).getFull());
        runtimeTask.getRuntimeDirectory().set(runtimeDirectory);
        runtimeTask.getRuntimeName().set(spec.getVersionedName());
        runtimeTask.getJavaVersion().convention(spec.getProject().getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion());

        for (TaskCustomizer<? extends Task> taskCustomizer : spec.getTaskCustomizers().get(step)) {
            taskCustomizer.apply(runtimeTask);
        }
    }

    public static void configureCommonRuntimeTaskParameters(Runtime runtime, CommonRuntimeDefinition<?> runtimeDefinition, File workingDirectory) {
        configureCommonRuntimeTaskParameters(runtime, runtimeDefinition.getSpecification(), workingDirectory);
    }

    public static void configureCommonRuntimeTaskParameters(Runtime runtime, String stepName, CommonRuntimeSpecification specification, File workingDirectory) {
        configureCommonRuntimeTaskParameters(runtime, Collections.emptyMap(), stepName, specification, workingDirectory);
    }

    private static void configureCommonRuntimeTaskParameters(Runtime runtime, CommonRuntimeSpecification specification, File workingDirectory) {
        configureCommonRuntimeTaskParameters(runtime, runtime.getName(), specification, workingDirectory);
    }

    public static Map<GameArtifact, TaskProvider<? extends WithOutput>> buildDefaultArtifactProviderTasks(final Specification spec) {
        final MinecraftArtifactCache artifactCache = spec.getProject().getExtensions().getByType(MinecraftArtifactCache.class);
        return artifactCache.cacheGameVersionTasks(spec.getProject(), spec.getMinecraftVersion(), spec.getDistribution());
    }
    
    @Override
    public Project getProject() {
        return project;
    }
    
    @Override
    public final Map<String, D> getDefinitions() {
        return this.definitions;
    }

    @Deprecated
    public final Provider<Map<String, D>> getRuntimes() {
        return project.provider(this::getDefinitions);
    }

    @Override
    @NotNull
    public final D maybeCreate(final Action<B> configurator) {
        final S spec = createSpec(configurator);
        if (definitions.containsKey(spec.getIdentifier()))
            return definitions.get(spec.getIdentifier());

        return create(configurator);
    }

    @Override
    public D maybeCreateFor(Dependency dependency, Action<B> configurator) {
        final D result = maybeCreate(configurator);
        dependencies.put(result.getSpecification().getIdentifier(), dependency);
        return result;
    }

    @Override
    @NotNull
    public final D create(final Action<B> configurator) {
        final S spec = createSpec(configurator);

        if (project.getExtensions().getByType(RuntimesExtension.class).definitionExists(spec.getIdentifier()))
            throw new IllegalArgumentException(String.format("Runtime with identifier '%s' already exists", spec.getIdentifier()));

        final D runtime = doCreate(spec);
        definitions.put(spec.getIdentifier(), runtime);
        return runtime;
    }

    @Override
    public D create(Dependency dependency, Action<B> configurator) {
        final D result = create(configurator);
        dependencies.put(result.getSpecification().getIdentifier(), dependency);
        return result;
    }

    @NotNull
    protected abstract D doCreate(final S spec);

    @NotNull
    private S createSpec(Action<B> configurator) {
        final B builder = createBuilder();
        configurator.execute(builder);
        return builder.build();
    }

    @Override
    @NotNull
    public final D getByName(final String name) {
        return this.definitions.computeIfAbsent(name, (n) -> {
            throw new RuntimeException(String.format("Failed to find runtime with name: %s", n));
        });
    }

    @Override
    @Nullable
    public final D findByNameOrIdentifier(final String name) {
        final D byIdentifier = this.definitions.get(name);
        if (byIdentifier != null)
            return byIdentifier;

        return definitions.values().stream().filter(r -> r.getSpecification().getVersionedName().equals(name)).findAny().orElse(null);
    }

    protected abstract B createBuilder();

    @Override
    @NotNull
    public Set<D> findIn(final Configuration configuration) {

        final Repository repository = project.getExtensions().getByType(Repository.class);

        final Set<D> directDependency = configuration.getAllDependencies().
                stream().flatMap(dep -> getDefinitions().values().stream()
                        .filter(runtime -> dependencies.containsKey(runtime.getSpecification().getIdentifier()))
                        .filter(runtime -> repository.getEntries().stream().anyMatch(entry -> entry.getOriginal().equals(
                                dependencies.get(runtime.getSpecification().getIdentifier())
                        ))))
                .collect(Collectors.toSet());

        if (!directDependency.isEmpty())
            return directDependency;

        return project.getConfigurations().stream()
                .filter(config -> config.getHierarchy().contains(configuration))
                .flatMap(config -> config.getAllDependencies().stream())
                .flatMap(dep -> getDefinitions().values().stream()
                        .filter(runtime -> dependencies.containsKey(runtime.getSpecification().getIdentifier()))
                        .filter(runtime -> repository.getEntries().stream().anyMatch(entry -> entry.getOriginal().equals(
                                dependencies.get(runtime.getSpecification().getIdentifier())
                        ))))
                .collect(Collectors.toSet());
    }

    protected final TaskProvider<DownloadAssets> createDownloadAssetsTasks(final CommonRuntimeSpecification specification, final Map<String, String> symbolicDataSources, final File runtimeDirectory, final VersionJson versionJson) {
        return specification.getProject().getTasks().register(CommonRuntimeUtils.buildTaskName(specification, "downloadAssets"), DownloadAssets.class, task -> {
            task.getVersionJson().set(versionJson);
        });
    }

    protected final TaskProvider<DownloadAssets> createDownloadAssetsTasks(final CommonRuntimeSpecification specification, final File runtimeDirectory, final VersionJson versionJson) {
        return createDownloadAssetsTasks(specification, Collections.emptyMap(), runtimeDirectory, versionJson);
    }

    protected final TaskProvider<ExtractNatives> createExtractNativesTasks(final CommonRuntimeSpecification specification, final Map<String, String> symbolicDataSources, final File runtimeDirectory, final VersionJson versionJson) {
        return specification.getProject().getTasks().register(CommonRuntimeUtils.buildTaskName(specification, "extractNatives"), ExtractNatives.class, task -> {
            task.getVersionJson().set(versionJson);

            configureCommonRuntimeTaskParameters(task, symbolicDataSources, "extractNatives", specification, runtimeDirectory);
            task.getOutputDirectory().set(task.getStepsDirectory().map(dir -> dir.dir("extractNatives")));
        });
    }

    protected final TaskProvider<ExtractNatives> createExtractNativesTasks(final CommonRuntimeSpecification specification, final File runtimeDirectory, final VersionJson versionJson) {
        return createExtractNativesTasks(specification, Collections.emptyMap(), runtimeDirectory, versionJson);
    }
}
