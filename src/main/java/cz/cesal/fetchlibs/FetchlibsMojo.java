package cz.cesal.fetchlibs;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.filter.PatternInclusionsDependencyFilter;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

/**
 * @author David ČESAL (David(at)Cesal.cz)
 */
@Mojo(name = "fetchlibs")
public class FetchlibsMojo extends AbstractMojo {

	private static final Pattern DEPENDENCY_PATTERN = Pattern.compile("^([^ \\/]+)\\/([^ \\/]+)\\/version *= *([^ ]+)$\\s[\\s\\S]+?\\/type *= *([^ ]+)$\\s[\\s\\S]+?\\/scope *= *([^ ]+)$", Pattern.MULTILINE);
	private static final Pattern MAIN_CLASS_PATTERN = Pattern.compile("Main\\-Class: *([^\\s]+)", Pattern.MULTILINE);

	@Component
	private MavenProject mavenProject;

	@Component
	private MavenSession mavenSession;

	@Component
	private BuildPluginManager pluginManager;

	@Component
	private RepositorySystem repositorySystem;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
	private RepositorySystemSession repositorySystemSession;

	@Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
	private List<RemoteRepository> remoteRepositories;

	@Parameter(property = "fetchlibs.source", defaultValue = "app.jar")
	private String source;

	@Parameter(property = "fetchlibs.target", defaultValue = "./libs")
	private String target;

	@Parameter(property = "fetchlibs.startClassToFile", defaultValue = "")
	private String startClassToFile;

	@Parameter
	private List<Exclusion> exclusions;

	@Parameter
	private List<SpecificRepository> specificRepositories;

	@Parameter
	private List<ExcludedRepository> excludedRepositories;

	private CollectRequest globalCollectRequest = null;
	private Map<Pattern, CollectRequest> specificCollectRequests = new HashMap<Pattern, CollectRequest>();

	private Settings effectiveSettings;

	public static final String userHome = System.getProperty("user.home");
	public static final File userMavenConfigurationHome = new File(userHome, ".m2");
	public static final String envM2Home = System.getenv("M2_HOME");
	public static final File DEFAULT_USER_SETTINGS_FILE = new File(userMavenConfigurationHome, "settings.xml");
	public static final File DEFAULT_GLOBAL_SETTINGS_FILE = new File(System.getProperty("maven.home", envM2Home != null ? envM2Home : ""), "conf/settings.xml");

	public void execute() throws MojoExecutionException, MojoFailureException {
		/* --- Check parameters before work --- */
		if (source == null || source.isEmpty()) {
			getLog().error("No source parameter defined. Specify path to your JAR package with libraries definition.");
			throw new MojoExecutionException("No source parameter defined. Specify path to your JAR package with libraries definition.");
		}
		File srcFile = new File(source);
		if (!srcFile.isFile() || !srcFile.exists()) {
			getLog().error("Specified source file does NOT exist in " + srcFile.getAbsolutePath());
			throw new MojoExecutionException("Specified source file does NOT exist");
		}

		File targetDir = new File(target);
		if (!targetDir.isDirectory() || !targetDir.exists()) {
			getLog().info("Creating non-existing target directory " + targetDir.getAbsolutePath());
			if (!targetDir.mkdir()) {
				getLog().error("Specified target directory cannot be created");
				throw new MojoExecutionException("Specified target directory cannot be created");
			}
		}
		try {
			getLog().info("Loading libraries definitions from " + srcFile.getCanonicalPath());
			try {
				/* --- Do the actual work --- */
				prepareDependencies(srcFile.getCanonicalFile(), targetDir.getCanonicalFile());
			} catch (IOException e) {
				e.printStackTrace();
				getLog().error("Cannot get canonical path of target directory - " + targetDir.getAbsolutePath());
			}
		} catch (IOException e1) {
			e1.printStackTrace();
			throw new MojoExecutionException("Cannot get canonical path of source file - " + srcFile.getAbsolutePath());
		}
	}

	private void prepareDependencies(File src, File dir) throws MojoExecutionException, MojoFailureException {
		ZipFile zipFile = null;
		try {
			zipFile = new ZipFile(src);
			getLog().debug("Reading ZIP file " + src.getAbsolutePath());
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			boolean found = false;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				getLog().debug(entry.getName());
				if (entry.getName().equalsIgnoreCase("META-INF/maven/dependencies.properties")) {
					getLog().info("Reading " + entry.getName() + " file");
					InputStream stream = zipFile.getInputStream(entry);
					prepareDependenciesFrom(stream, dir);
					found = true;
					break;
				} else if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF") && startClassToFile != null && !startClassToFile.isEmpty()) {
					String str = IOUtils.toString(zipFile.getInputStream(entry), "UTF-8");
					Matcher m = MAIN_CLASS_PATTERN.matcher(str);
					if (m.find()) {
						String startClass = m.group(1);
						File targetFile = new File(startClassToFile);
						FileUtils.writeStringToFile(targetFile, startClass, "UTF-8");
						getLog().info("Writing start class name " + startClass + " into file " + targetFile.getAbsolutePath());
					}
				}
			}
			if (!found) {
				getLog().warn("META-INF/maven/dependencies.properties NOT found in " + src.getAbsolutePath());
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new MojoExecutionException("Cannot process ZIP archive from " + src.getAbsolutePath());
		} finally {
			if (zipFile != null) {
				try {
					zipFile.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void prepareDependenciesFrom(InputStream is, File dir) throws MojoExecutionException, IOException {
		String str = IOUtils.toString(is, "UTF-8");
		getLog().debug(str);

		List<MavenDependency> list = new ArrayList<MavenDependency>();
		Matcher m = DEPENDENCY_PATTERN.matcher(str);
		while (m.find()) {
			MavenDependency dep = new MavenDependency();
			dep.setGroupId(m.group(1).trim());
			dep.setArtifactId(m.group(2).trim());
			dep.setVersion(m.group(3).trim());
			dep.setPackaging(m.group(4).trim());
			dep.setScope(m.group(5).trim());
			list.add(dep);

			getLog().debug(dep.toString());
		}

		if (list.size() >= 1) {
			getLog().info("Found " + list.size() + " dependencies, preparing them into " + dir.getAbsolutePath());

			try {
				prepareSettings();
			} catch (SettingsBuildingException e) {
				e.printStackTrace();
				getLog().error("Cannot load settings");
			}

			// Extract repositories
			int i = 1;
			for (MavenDependency d : list) {
				extractRepositories(d, dir, i, list.size());
				i++;
			}

			getLog().info("");
			listExclusions();
			getLog().info("");
			// Prepare libraries
			i = 1;
			for (MavenDependency d : list) {
				prepareDependency(d, dir, i, list.size());
				i++;
			}

			getLog().info("All libraries are now prepared in " + dir.getCanonicalPath());
		} else {
			getLog().info("No dependencies found");
		}
	}

	private static final DependencyFilter RUNTIME_FILTER = DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME);
	private static final DependencyFilter NOT_OPTIONAL_FILTER = new DependencyFilter() {
		@Override
		public boolean accept(DependencyNode node, List<DependencyNode> parents) {
			return !node.getDependency().isOptional();
		}
	};

	private Set<String> repos = new HashSet<String>();

	private void extractRepositories(MavenDependency d, File dir, int current, int total) throws MojoExecutionException, IOException {
		String depPatternPom = d.getGroupId() + ":" + d.getArtifactId() + ":pom:" + d.getVersion();

		getLog().debug("Extracting repositories from " + current + "/" + total + " - " + depPatternPom);

		// We create a collect request here
		// By adding the remote repositories we force Aether to download artifacts if they
		// are not already in the local repository
		prepareRemoteReposCollection();

		// 1 - Fetch POM and parse repositories from it
		DependencyFilter pomFilter = new PatternInclusionsDependencyFilter(depPatternPom);

		globalCollectRequest.setRoot(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(d.getGroupId(), d.getArtifactId(), "pom", d.getVersion()), d.getScope()));
		DependencyRequest theDependencyRequest = new DependencyRequest(globalCollectRequest, DependencyFilterUtils.andFilter(pomFilter, NOT_OPTIONAL_FILTER));
		try {
			DependencyResult theDependencyResult = repositorySystem.resolveDependencies(repositorySystemSession, theDependencyRequest);
			for (ArtifactResult theArtifactResult : theDependencyResult.getArtifactResults()) {
				Artifact theResolved = theArtifactResult.getArtifact();

				File pomFile = theResolved.getFile();
				getLog().debug("POM file stored in " + pomFile.getAbsolutePath());
				MavenProject proj = parsePom(pomFile);
				if (proj != null) {
					addAllRepositories(proj);
				} else {
					getLog().warn("Cannot read POM file from " + pomFile.getAbsolutePath());
				}
			}
		} catch (DependencyResolutionException e) {
			e.printStackTrace();
			throw new MojoExecutionException("Error while resolving POM dependency", e);
		}
	}

	private void prepareDependency(MavenDependency d, File dir, int current, int total) throws MojoExecutionException, IOException {
		String depPatternDef = d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getPackaging() + ":" + d.getVersion();

		getLog().info("Dependency " + current + "/" + total + " - " + depPatternDef);

		// We create a collect request here
		// By adding the remote repositories we force Aether to download artifacts if they
		// are not already in the local repository
		prepareRemoteReposCollection();

		// 2 - Fetch actual dependency
		DependencyFilter patternFilter = new PatternInclusionsDependencyFilter(depPatternDef);

		DependencyRequest theDependencyRequest = null;
		if (specificCollectRequests != null && specificCollectRequests.size() >= 1) {
			boolean matches = false;
			for (Pattern p : specificCollectRequests.keySet()) {
				Matcher m = p.matcher(d.getGroupId());
				if (m.matches()) {
					CollectRequest coll = specificCollectRequests.get(p);
					if (coll != null) {
						matches = true;
						coll.setRoot(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getPackaging(), d.getVersion()), d.getScope()));
						theDependencyRequest = new DependencyRequest(coll, DependencyFilterUtils.andFilter(RUNTIME_FILTER, patternFilter, NOT_OPTIONAL_FILTER));
						getLog().info("           Using specific repository for " + d.getGroupId() + ":" + d.getArtifactId());
					} else {
						getLog().error("Specific repository for " + d.getGroupId() + ":" + d.getArtifactId() + " has NULL CollectRequest");
					}
				}
			}
			if (!matches) {
				globalCollectRequest.setRoot(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getPackaging(), d.getVersion()), d.getScope()));
				theDependencyRequest = new DependencyRequest(globalCollectRequest, DependencyFilterUtils.andFilter(RUNTIME_FILTER, patternFilter, NOT_OPTIONAL_FILTER));
			}
		} else {
			globalCollectRequest.setRoot(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getPackaging(), d.getVersion()), d.getScope()));
			theDependencyRequest = new DependencyRequest(globalCollectRequest, DependencyFilterUtils.andFilter(RUNTIME_FILTER, patternFilter, NOT_OPTIONAL_FILTER));
		}

		try {
			DependencyResult theDependencyResult = repositorySystem.resolveDependencies(repositorySystemSession, theDependencyRequest);
			for (ArtifactResult theArtifactResult : theDependencyResult.getArtifactResults()) {
				Artifact resolved = theArtifactResult.getArtifact();
				if (isInExclusions(resolved)) {
					getLog().info("   Not including excluded dependency");
					continue;
				}

				File depFile = resolved.getFile();
				getLog().debug("   File stored in " + depFile.getAbsolutePath());

				if (!depFile.getParentFile().equals(dir)) {
					File targetFile = new File(dir.getAbsolutePath() + File.separator + resolved.getGroupId().replace('.', '-') + "-" + depFile.getName());
					if (!targetFile.exists()) {
						FileUtils.copyFile(depFile, targetFile);
						getLog().info("   [COPYING] File to " + targetFile.getAbsolutePath() + " from " + depFile.getAbsolutePath());
					} else if (targetFile.length() != depFile.length()) {
						targetFile.delete();
						FileUtils.copyFile(depFile, targetFile);
						getLog().info("   [REPLACING] File " + targetFile.getAbsolutePath() + " from " + depFile.getAbsolutePath() + " (differs in length - " + targetFile.length() + " vs " + depFile.length() + ")");
					} else {
						getLog().info("   [OK] File already stored in " + targetFile.getAbsolutePath());
					}
				}
			}
		} catch (DependencyResolutionException e) {
			e.printStackTrace();
			throw new MojoExecutionException("Error while resolving dependency", e);
		}
		getLog().info("");
	}

	private void listExclusions() {
		if (exclusions != null && exclusions.size() >= 1) {
			getLog().info("There are " + exclusions.size() + " exclusions:");
			for (Exclusion e : exclusions) {
				getLog().info("   " + e.getGroupId() + ":" + e.getArtifactId());
			}
		} else {
			getLog().info("There are no exclusions.");
		}
	}

	private boolean isInExclusions(Artifact artifact) {
		if (exclusions != null && exclusions.size() >= 1) {
			for (Exclusion e : exclusions) {
				if (e.getGroupId().equalsIgnoreCase(artifact.getGroupId()) && e.getArtifactId().equalsIgnoreCase(artifact.getArtifactId())) {
					return true;
				}
			}
		}
		return false;
	}

	private void addAllRepositories(MavenProject proj) {
		if (proj != null) {
			repos: for (Repository repo : proj.getRepositories()) {
				if (repos.add(repo.getUrl())) {
					Builder builder = new RemoteRepository.Builder(repo.getId(), "default", repo.getUrl());
					Server server = getServerFromSettings(repo.getId());
					if (server != null) {
						builder.setAuthentication(new AuthenticationBuilder().addUsername(server.getUsername()).addPassword(server.getPassword()).build());
					}
					RemoteRepository remoteRepo = builder.build();

					if (excludedRepositories != null) {
						for (ExcludedRepository e : excludedRepositories) {
							if (remoteRepo.getUrl().matches(e.getUrl())) {
								getLog().info("[ExcludedRepo] Skipping repository " + remoteRepo.getUrl() + " (ID " + remoteRepo.getId() + ") - is excluded by URL");
								continue repos;
							}
						}
					}

					getLog().info("[REPOSITORY] Adding repository " + remoteRepo.getUrl() + " (ID " + remoteRepo.getId() + ") found in POM " + proj.getGroupId() + ":" + proj.getArtifactId());

					boolean isSpecific = false;
					if (specificRepositories != null) {
						for (SpecificRepository r : specificRepositories) {
							if (r.getRepositoryId().equalsIgnoreCase(remoteRepo.getId())) {
								isSpecific = true;
								Pattern p = Pattern.compile(r.getGroupId());
								CollectRequest req = new CollectRequest();
								req.addRepository(remoteRepo);
								this.specificCollectRequests.put(p, req);
								getLog().info("             Repository is specific to groupId pattern " + r.getGroupId());
								break;
							}
						}
					}

					if (!isSpecific) {
						globalCollectRequest.addRepository(remoteRepo);
					}

					if (server != null) {
						getLog().info("      [AUTH] Setting repository " + repo.getId() + " authentication username " + server.getUsername() + " from settings");
					}
				}
			}
			if (proj.getParent() != null) {
				addAllRepositories(proj.getParent());
			}
		}
	}

	private Server getServerFromSettings(String repoId) {
		if (effectiveSettings != null) {
			for (Server server : effectiveSettings.getServers()) {
				if (server.getId().equalsIgnoreCase(repoId)) {
					return server;
				}
			}
		} else {
			getLog().warn("Maven settings is NULL, cannot load server authentication");
		}
		return null;
	}

	private MavenProject parsePom(File pomFile) {
		Model model = null;
		FileReader reader = null;
		MavenXpp3Reader mavenreader = new MavenXpp3Reader();
		try {
			reader = new FileReader(pomFile);
			model = mavenreader.read(reader);
			model.setPomFile(pomFile);
			MavenProject project = new MavenProject(model);
			return project;
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return null;
	}

	private void prepareRemoteReposCollection() {
		if (globalCollectRequest == null) {
			globalCollectRequest = new CollectRequest();
			for (RemoteRepository theRepository : remoteRepositories) {
				getLog().info("Adding remote repository ID " + theRepository.getId() + " - " + theRepository.getUrl());
				globalCollectRequest.addRepository(theRepository);
			}
		}
	}

	private void prepareSettings() throws SettingsBuildingException {
		getLog().info("Loading Maven settings");

		SettingsBuildingRequest settingsBuildingRequest = new DefaultSettingsBuildingRequest();
		settingsBuildingRequest.setSystemProperties(System.getProperties());
		settingsBuildingRequest.setUserSettingsFile(DEFAULT_USER_SETTINGS_FILE);
		settingsBuildingRequest.setGlobalSettingsFile(DEFAULT_GLOBAL_SETTINGS_FILE);

		SettingsBuildingResult settingsBuildingResult;
		DefaultSettingsBuilderFactory mvnSettingBuilderFactory = new DefaultSettingsBuilderFactory();
		DefaultSettingsBuilder settingsBuilder = mvnSettingBuilderFactory.newInstance();
		settingsBuildingResult = settingsBuilder.build(settingsBuildingRequest);

		effectiveSettings = settingsBuildingResult.getEffectiveSettings();
	}
}