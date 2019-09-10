package ai.turbochain.ipex.wallet.service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.crypto.CipherException;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.utils.Convert;

import ai.turbochain.ipex.wallet.entity.Account;
import ai.turbochain.ipex.wallet.entity.Coin;
import ai.turbochain.ipex.wallet.util.MessageResult;
import io.api.etherscan.core.impl.EtherScanApi;
import io.api.etherscan.model.Balance;

@Component
public class EthService {
	private Logger logger = LoggerFactory.getLogger(EthService.class);
	@Autowired
	private EtherScanApi etherScanApi;
	@Autowired
	private Coin coin;
	@Autowired
	private PaymentHandler paymentHandler;
	@Autowired
	private AccountService accountService;

	public String createNewWallet(String account, String password) throws NoSuchAlgorithmException,
			NoSuchProviderException, InvalidAlgorithmParameterException, CipherException, IOException, CipherException {
		String fileName = WalletUtils.generateNewWalletFile(password, new File(coin.getKeystorePath()), true);
		Credentials credentials = WalletUtils.loadCredentials(password, coin.getKeystorePath() + "/" + fileName);
		String address = credentials.getAddress();
		accountService.saveOne(account, fileName, address, password);
		return address;
	}

	/**
	 * 同步余额
	 *
	 * @param address
	 * @throws IOException
	 */
	public void syncAddressBalance(String address) throws IOException {
		BigDecimal balance = getBalance(address);
		accountService.updateBalance(address, balance);
	}

	public MessageResult transferFromWithdrawWallet(String toAddress, BigDecimal amount, boolean sync,
			String withdrawId) {
		return transfer(coin.getKeystorePath() + "/" + coin.getWithdrawWallet(), coin.getWithdrawWalletPassword(),
				toAddress, amount, sync, withdrawId);
	}

	public MessageResult transfer(String walletFile, String password, String toAddress, BigDecimal amount, boolean sync,
			String withdrawId) {
		Credentials credentials;
		try {
			credentials = WalletUtils.loadCredentials(password, walletFile);
		} catch (IOException e) {
			e.printStackTrace();
			return new MessageResult(500, "钱包文件不存在");
		} catch (CipherException e) {
			e.printStackTrace();
			return new MessageResult(500, "解密失败，密码不正确");
		}
		if (sync) {
			return paymentHandler.transferEth(credentials, toAddress, amount);
		} else {
			paymentHandler.transferEthAsync(credentials, toAddress, amount, withdrawId);
			return new MessageResult(0, "提交成功");
		}
	}

	public BigDecimal getBalance(String address) throws IOException {
		Balance balance = etherScanApi.account().balance(address);
		return Convert.fromWei(balance.getWei().toString(), Convert.Unit.ETHER);
	}

	public BigInteger getGasPrice() throws IOException {
		BigInteger baseGasPrice = etherScanApi.proxy().gasPrice();
		return new BigDecimal(baseGasPrice).multiply(coin.getGasSpeedUp()).toBigInteger();
	}

	public MessageResult transferFromWallet(String address, BigDecimal amount, BigDecimal fee, BigDecimal minAmount) {
		logger.info("transferFromWallet 方法");
		List<Account> accounts = accountService.findByBalance(minAmount);
		if (accounts == null || accounts.size() == 0) {
			MessageResult messageResult = new MessageResult(500, "没有满足条件的转账账户(大于0.1)!");
			logger.info(messageResult.toString());
			return messageResult;
		}
		BigDecimal transferredAmount = BigDecimal.ZERO;
		for (Account account : accounts) {
			BigDecimal realAmount = account.getBalance().subtract(fee);
			if (realAmount.compareTo(amount.subtract(transferredAmount)) > 0) {
				realAmount = amount.subtract(transferredAmount);
			}
			MessageResult result = transfer(coin.getKeystorePath() + "/" + account.getWalletFile(), "", address,
					realAmount, true, "");
			if (result.getCode() == 0 && result.getData() != null) {
				logger.info("transfer address={},amount={},txid={}", account.getAddress(), realAmount,
						result.getData());
				transferredAmount = transferredAmount.add(realAmount);
				try {
					syncAddressBalance(account.getAddress());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			if (transferredAmount.compareTo(amount) >= 0) {
				break;
			}
		}
		MessageResult result = new MessageResult(0, "success");
		result.setData(transferredAmount);
		return result;
	}

	public MessageResult transferToken(String fromAddress, String toAddress, BigDecimal amount, boolean sync) {
		Account account = accountService.findByAddress(fromAddress);
		Credentials credentials;
		try {
			credentials = WalletUtils.loadCredentials("", coin.getKeystorePath() + "/" + account.getWalletFile());
		} catch (IOException e) {
			e.printStackTrace();
			return new MessageResult(500, "私钥文件不存在");
		} catch (CipherException e) {
			e.printStackTrace();
			return new MessageResult(500, "解密失败，密码不正确");
		}
		if (sync) {
			return paymentHandler.transferToken(credentials, toAddress, amount);
		} else {
			paymentHandler.transferTokenAsync(credentials, toAddress, amount, "");
			return new MessageResult(0, "提交成功");
		}
	}

	public MessageResult transferTokenFromWithdrawWallet(String toAddress, BigDecimal amount, boolean sync,
			String withdrawId) {
		Credentials credentials;
		try {
			// 解锁提币钱包
			credentials = WalletUtils.loadCredentials(coin.getWithdrawWalletPassword(),
					coin.getKeystorePath() + "/" + coin.getWithdrawWallet());
		} catch (IOException e) {
			e.printStackTrace();
			return new MessageResult(500, "私钥文件不存在");
		} catch (CipherException e) {
			e.printStackTrace();
			return new MessageResult(500, "解密失败，密码不正确");
		}
		if (sync) {
			return paymentHandler.transferToken(credentials, toAddress, amount);
		} else {
			paymentHandler.transferTokenAsync(credentials, toAddress, amount, withdrawId);
			return new MessageResult(0, "提交成功");
		}
	}

	public BigDecimal getMinerFee(BigInteger gasLimit) throws IOException {
		BigDecimal fee = new BigDecimal(getGasPrice().multiply(gasLimit));
		return Convert.fromWei(fee, Convert.Unit.ETHER);
	}

	public Boolean isTransactionSuccess(String txid) throws IOException {
		try {
			Boolean isTransactionSuccess = etherScanApi.txs().receiptStatus(txid).get();
			return isTransactionSuccess;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
}
