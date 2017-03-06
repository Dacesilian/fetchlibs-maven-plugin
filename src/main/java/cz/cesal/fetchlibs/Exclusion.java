package cz.cesal.fetchlibs;

public class Exclusion {

	private String groupId;
	private String artifactId;

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(final String groupId) {
		this.groupId = groupId;
	}

	public String getArtifactId() {
		return artifactId;
	}

	public void setArtifactId(final String artifactId) {
		this.artifactId = artifactId;
	}

	@Override
	public String toString() {
		return "Exclusion [" + (groupId != null ? "groupId=" + groupId + ", " : "") + (artifactId != null ? "artifactId=" + artifactId : "") + "]";
	}

}