package ai.turbochain.ipex.wallet.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.api.etherscan.core.impl.EtherScanApi;

@Configuration
public class EthConfig {

	private Logger logger = LoggerFactory.getLogger(EthConfig.class);

	@Bean
	public EtherScanApi etherscanApi(@Value("${coin.apiKey}") String apiKey) {
		try {
			logger.info("apiKey={}", apiKey);
			EtherScanApi etherScanApi = new EtherScanApi(apiKey);
			logger.info("=============================");
			logger.info("client={}", etherScanApi);
			logger.info("=============================");
			return etherScanApi;
		} catch (Exception e) {
			logger.info("init ipex-ethscan 客户端 failed");
			e.printStackTrace();
			return null;
		}
	}
}
