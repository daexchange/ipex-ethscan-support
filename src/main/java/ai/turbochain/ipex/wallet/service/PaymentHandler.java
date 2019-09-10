package ai.turbochain.ipex.wallet.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import ai.turbochain.ipex.wallet.entity.Coin;
import ai.turbochain.ipex.wallet.entity.Contract;
import ai.turbochain.ipex.wallet.entity.Payment;
import ai.turbochain.ipex.wallet.util.MessageResult;
import io.api.etherscan.core.impl.EtherScanApi;

/**
 * ETH与Token付款模块，支持同步任务与异步任务，在单地址可能出现连续付款的情况的下，使用异步阶列
 */
@Component
public class PaymentHandler {
	private Logger logger = LoggerFactory.getLogger(PaymentHandler.class);
	@Autowired
	private EtherScanApi etherScanApi;
	@Autowired
	private EthService ethService;
	@Autowired(required = false)
	private Contract contract;
	@Autowired
	private Coin coin;
	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;
	@Autowired(required = false)
	private EtherscanApi etherscanApi;
	private Payment current;
	private LinkedList<Payment> tasks = new LinkedList<>();
	private int checkTimes = 0;
	private int maxCheckTimes = 100;

	public void transferTokenAsync(Credentials credentials, String to, BigDecimal amount, String withdrawId) {
		Payment payment = Payment.builder().credentials(credentials).amount(amount).to(to).txBizNumber(withdrawId)
				.unit(coin.getUnit()).build();
		synchronized (tasks) {
			tasks.addLast(payment);
		}
	}

	public void notify(Payment payment, int status) {
		JSONObject json = new JSONObject();
		json.put("withdrawId", payment.getTxBizNumber());
		json.put("txid", payment.getTxid());
		json.put("status", status);
		kafkaTemplate.send("withdraw-notify", coin.getName(), JSON.toJSONString(json));
	}

	public void transferEthAsync(Credentials credentials, String to, BigDecimal amount, String withdrawId) {
		Payment payment = Payment.builder().credentials(credentials).amount(amount).to(to).txBizNumber(withdrawId)
				.unit("ETH").build();
		synchronized (tasks) {
			tasks.addLast(payment);
		}
	}

	public MessageResult transferEth(Credentials credentials, String to, BigDecimal amount) {
		Payment payment = Payment.builder().credentials(credentials).amount(amount).to(to).unit("ETH").build();
		return transferEth(payment);
	}

	public MessageResult transferEth(Payment payment) {
		try {
			BigInteger nonce = BigInteger
					.valueOf(etherScanApi.proxy().txSendCount(payment.getCredentials().getAddress()));
			BigInteger gasPrice = ethService.getGasPrice();
			BigInteger value = Convert.toWei(payment.getAmount(), Convert.Unit.ETHER).toBigInteger();
			BigInteger maxGas = coin.getGasLimit();
			logger.info("value={},gasPrice={},gasLimit={},nonce={},address={}", value, gasPrice, maxGas, nonce,
					payment.getTo());
			RawTransaction rawTransaction = RawTransaction.createEtherTransaction(nonce, gasPrice, maxGas,
					payment.getTo(), value);
			byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, payment.getCredentials());
			String hexValue = Numeric.toHexString(signedMessage);
			String txid = etherScanApi.proxy().txSendRaw(hexValue).get();
			System.out.println("交易编号======="+txid);
			/*if (etherscanApi != null) {
				logger.info("=====发送Etherscan广播交易======");
				etherscanApi.sendRawTransaction(hexValue);
			}*/
			MessageResult mr = new MessageResult(0, "success");
			// 暂时修改
			mr.setData(txid);
			return mr;

		} catch (Exception e) {
			e.printStackTrace();
			return new MessageResult(500, "交易失败,error:" + e.getMessage());
		}
	}

	public MessageResult transferToken(Payment payment) {
		try {
			BigInteger nonce = BigInteger
					.valueOf(etherScanApi.proxy().txSendCount(payment.getCredentials().getAddress()));
			BigInteger gasPrice = ethService.getGasPrice();
			BigInteger value = Convert.toWei(payment.getAmount(), Convert.Unit.ETHER).toBigInteger();
			Function fn = new Function("transfer", Arrays.asList(new Address(payment.getTo()), new Uint256(value)),
					Collections.<TypeReference<?>>emptyList());
			String data = FunctionEncoder.encode(fn);
			BigInteger maxGas = contract.getGasLimit();
			logger.info("from={},value={},gasPrice={},gasLimit={},nonce={},address={}",
					payment.getCredentials().getAddress(), value, gasPrice, maxGas, nonce, payment.getTo());
			RawTransaction rawTransaction = RawTransaction.createTransaction(nonce, gasPrice, maxGas,
					contract.getAddress(), data);
			byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, payment.getCredentials());
			String hexValue = Numeric.toHexString(signedMessage);
			logger.info("hexRawValue={}", hexValue);
			if (etherscanApi != null) {
				logger.info("=====发送Etherscan广播交易======");
				etherscanApi.sendRawTransaction(hexValue);
			}
			// 暂时修改
			payment.setTxid("");
			MessageResult mr = new MessageResult(0, "success");
			// 暂时修改
			mr.setData("");
			return mr;
		} catch (Exception e) {
			e.printStackTrace();
			return new MessageResult(500, "交易失败,error:" + e.getMessage());
		}
	}

	public MessageResult transferToken(Credentials credentials, String to, BigDecimal amount) {
		Payment payment = Payment.builder().credentials(credentials).amount(amount).to(to).unit(coin.getUnit()).build();
		return transferToken(payment);
	}

	/**
	 * 检查当前任务是否支付完成
	 */
	@Scheduled(cron = "0/30 * * * * *")
	public synchronized void checkJob() {
		logger.info("检查付款任务状态");
//        && StringUtils.isNotEmpty(current.getTxid())
		if (current != null) {
			synchronized (current) {
				try {
					checkTimes++;
					if (ethService.isTransactionSuccess(current.getTxid())) {
						logger.info("转账{}已成功,检查次数:{}", JSON.toJSON(current), checkTimes);
						notify(current, 1);
						current = null;
					} else {
						logger.info("转账{}未成功,检查次数:{}", JSON.toJSON(current), checkTimes);
						if (checkTimes > maxCheckTimes) {
							// 超时未成功
							notify(current, 0);
							current = null;
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} else {
			logger.info("无待确认的任务");
		}
	}

	public MessageResult transfer(Payment payment) {
		if (payment.getUnit().equalsIgnoreCase("ETH")) {
			return transferEth(payment);
		} else {
			return transferToken(payment);
		}
	}

	@Scheduled(cron = "0/30 * * * * *")
	public synchronized void doJob() {
		synchronized (tasks) {
			logger.info("开始执行付款任务，当前队列长度{}", tasks.size());
			if (current == null && tasks.size() > 0) {
				logger.info("开始执行付款任务:current---" + JSONObject.toJSONString(current));
				Payment payment = tasks.getFirst();
				MessageResult result = transfer(payment);
				if (result.getCode() == 0) {
					logger.info("------txID:" + result.getData().toString());
					payment.setTxid(result.getData().toString());
					tasks.removeFirst();
					current = payment;
					checkTimes = 0;
				}
			}
		}
	}
}
