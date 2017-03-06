package cz.cesal.fetchlibs;

/**
 * @author David ČESAL (David(at)Cesal.cz)
 */
public class MavenDependency {

	private String groupId;
	private String artifactId;
	private String version;
	private String packaging;
	private String scope;

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public String getArtifactId() {
		return artifactId;
	}

	public void setArtifactId(String artifactId) {
		this.artifactId = artifactId;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getPackaging() {
		return packaging;
	}

	public void setPackaging(String packaging) {
		this.packaging = packaging;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	@Override
	public String toString() {
		return "MavenDependency [groupId=" + groupId + ", artifactId=" + artifactId + ", version=" + version + ", packaging=" + packaging + ", scope=" + scope + "]";
	}

}
