package com.ecosystem.runtime;

import com.ecosystem.EcosystemMaster;
import com.ecosystem.EcosystemResponse;
import com.ecosystem.data.mongodb.ConnectionFactory;
import com.ecosystem.utils.GlobalSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProductMasterSuper {
	private static final Logger LOGGER = LogManager.getLogger(ProductMaster.class.getName());

	private GlobalSettings settings;
	private ConnectionFactory settingsConnection;
	private EcosystemMaster ecosystemMaster;
	private EcosystemResponse ecosystemResponse;

}
