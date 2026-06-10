package com.tricrotism.uworldguard;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class UWorldGuardLoader implements PluginLoader {

    @Override
    public void classloader(final PluginClasspathBuilder classpathBuilder) {
        final MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
            "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        resolver.addRepository(new RemoteRepository.Builder(
            "xenondevs", "default", "https://repo.xenondevs.xyz/releases/").build());

        resolver.addDependency(new Dependency(
            new DefaultArtifact("org.incendo:cloud-paper:2.0.0-beta.15"), null));
        resolver.addDependency(new Dependency(
            new DefaultArtifact("xyz.xenondevs.invui:invui:2.1.0"), null));
        resolver.addDependency(new Dependency(
            new DefaultArtifact("org.incendo:cloud-annotations:2.0.0"), null));
        resolver.addDependency(new Dependency(
            new DefaultArtifact("com.github.ben-manes.caffeine:caffeine:3.1.8"), null));
        resolver.addDependency(new Dependency(
            new DefaultArtifact("org.xerial:sqlite-jdbc:3.47.1.0"), null));

        classpathBuilder.addLibrary(resolver);
    }
}
