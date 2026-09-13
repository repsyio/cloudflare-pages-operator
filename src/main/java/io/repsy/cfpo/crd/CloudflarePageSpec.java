package io.repsy.cfpo.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import io.fabric8.crd.generator.annotation.PrinterColumn;
import io.fabric8.generator.annotation.Default;
import io.fabric8.generator.annotation.Pattern;
import io.fabric8.generator.annotation.Required;
import io.fabric8.generator.annotation.Size;
import io.fabric8.generator.annotation.ValidationRule;

@JsonInclude(JsonInclude.Include.NON_NULL)
@ValidationRule(
    value =
        "has(self.projectName) == has(oldSelf.projectName)"
            + " && (!has(self.projectName) || self.projectName == oldSelf.projectName)",
    message = "projectName is immutable")
public class CloudflarePageSpec {

  @Required
  @Size(min = 1)
  @PrinterColumn(name = "Image", priority = 1)
  @JsonPropertyDescription("Container image that contains the built static site.")
  private String image;

  @Required
  @Pattern("^/.+$")
  @JsonPropertyDescription("Absolute path of the directory inside the image to publish.")
  private String directory;

  @Required
  @Size(max = 253)
  @Pattern("^([a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
  @PrinterColumn(name = "Domain")
  @JsonPropertyDescription(
      "Custom domain for the site. When its zone is in the operator's Cloudflare account the"
          + " CNAME record is managed automatically.")
  private String domain;

  @Pattern("^[a-z0-9]([-a-z0-9]{0,56}[a-z0-9])?$")
  @JsonPropertyDescription(
      "Cloudflare Pages project name. Defaults to <namespace>-<name>. Immutable.")
  private String projectName;

  @Default("main")
  @JsonPropertyDescription("Production branch name of the Pages project.")
  private String branch;

  @JsonPropertyDescription(
      "Change this value to force a redeploy of the same image, e.g. when using a mutable tag.")
  private String revision;

  public String getImage() {
    return image;
  }

  public void setImage(String image) {
    this.image = image;
  }

  public String getDirectory() {
    return directory;
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public String getRevision() {
    return revision;
  }

  public void setRevision(String revision) {
    this.revision = revision;
  }
}
