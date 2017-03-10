package cz.cesal.fetchlibs;

public class SpecificRepository {

	private String groupId;
	private String repositoryId;

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public String getRepositoryId() {
		return repositoryId;
	}

	public void setRepositoryId(String repositoryId) {
		this.repositoryId = repositoryId;
	}

	@Override
	public String toString() {
		return "SpecificRepository [" + (groupId != null ? "groupId=" + groupId + ", " : "") + (repositoryId != null ? "repositoryId=" + repositoryId : "") + "]";
	}

}
