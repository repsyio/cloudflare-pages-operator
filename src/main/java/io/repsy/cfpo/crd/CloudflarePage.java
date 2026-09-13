package io.repsy.cfpo.crd;

import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/** A static site, taken from a directory inside a container image, served by Cloudflare Pages. */
@Group(CloudflarePage.GROUP)
@Version(CloudflarePage.VERSION)
@ShortNames("cfpage")
@AdditionalPrinterColumn(
    name = "Age",
    jsonPath = ".metadata.creationTimestamp",
    type = AdditionalPrinterColumn.Type.DATE)
public class CloudflarePage extends CustomResource<CloudflarePageSpec, CloudflarePageStatus>
    implements Namespaced {

  public static final String GROUP = "pages.repsy.io";
  public static final String VERSION = "v1alpha1";
}
