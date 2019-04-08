/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.azure.catalog.vm;

import org.ligoj.app.plugin.prov.azure.catalog.AzureComputePrice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure VM prices for an instance type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureVmPrice extends AzureComputePrice {

	/**
	 * RAM, in GiB.
	 */
	private double ram;

	/**
	 * Optional disk size, in GiB
	 */
	private int diskSize;

}