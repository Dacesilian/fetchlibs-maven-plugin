package cz.cesal.fetchlibs;

public class ExcludedRepository {

	private String url;

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	@Override
	public String toString() {
		return "ExcludedRepository [" + (url != null ? "url=" + url : "") + "]";
	}

}
