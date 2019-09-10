package ai.turbochain.ipex.wallet.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class EtherscanApi {
    private Logger logger = LoggerFactory.getLogger(EtherscanApi.class);
    private String token;

    public void sendRawTransaction(String hexValue){
        try {
            HttpResponse<String> response =  Unirest.post("https://api.etherscan.io/api")
                    .field("module","proxy")
                    .field("action","eth_sendRawTransaction")
                    .field("hex",hexValue)
                    .field("apikey",token)
                    .asString();
            logger.info("sendRawTransaction result = {}",response.getBody());
        } catch (UnirestException e) {
            e.printStackTrace();
        }
    }


    public boolean checkEventLog(final Long blockHeight,String address,String topic0,String txid){
        try {
            HttpResponse<String> response = Unirest.post("https://api.etherscan.io/api")
                    .field("module", "logs")
                    .field("action", "getLogs")
                    .field("fromBlock", blockHeight)
                    .field("toBlock",blockHeight)
                    .field("address",address)
                    .field("topic0",topic0)
                    .field("apikey", token)
                    .asString();
            logger.info("getLogs result = {}",response.getBody());
            JSONObject result = JSON.parseObject(response.getBody());
            if(result.getInteger("status")==0){
                return false;
            }
            else{
                JSONArray txs = result.getJSONArray("result");
                for(int i=0;i<txs.size();i++){
                    JSONObject item = txs.getJSONObject(i);
                    if(item.getString("transactionHash").equalsIgnoreCase(txid))return true;
                }
                return false;
            }

        }
        catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }
}
