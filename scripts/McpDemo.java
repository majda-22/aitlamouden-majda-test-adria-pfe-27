import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.math.BigDecimal;

/** Démonstration utilisant le SDK client MCP officiel, sans LLM. */
class McpDemo {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static void check(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
    static McpSyncClient connect(String base) {
        var client=McpClient.sync(HttpClientStreamableHttpTransport.builder(base).endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(15)).build();
        var init=client.initialize();
        System.out.println("initialize "+base+" : "+init.serverInfo()+" protocol="+init.protocolVersion());
        var tools=client.listTools().tools();
        for(var tool:tools) System.out.println("tools/list : "+tool.name()+" readOnly="+tool.annotations().readOnlyHint()+" schema="+JSON.writeValueAsString(tool.inputSchema()));
        return client;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> call(McpSyncClient client,String name,Map<String,Object> args,boolean error,String code) {
        var result=client.callTool(new McpSchema.CallToolRequest(name,args));
        System.out.println("tools/call "+name+" isError="+result.isError()+" : "+JSON.writeValueAsString(result.structuredContent()));
        check(Boolean.TRUE.equals(result.isError())==error,"Résultat tool inattendu : "+name);
        var data=(Map<String,Object>)result.structuredContent();
        if(code!=null) check(code.equals(data.get("code")),"Code métier incorrect");
        return data;
    }
    static Map<String,Object> createAccount(String base,String amount) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+"/api/accounts")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customerReference\":\"MCP-DEMO\",\"currency\":\"MAD\",\"balance\":"+amount+"}")).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==201,"Création du compte de démonstration échouée");
        return JSON.readValue(response.body(),Map.class);
    }
    public static void main(String[] args) throws Exception {
        String accountUrl=args[0],transactionUrl=args[1];
        if(args.length>2 && args[2].equals("outage")) {
            try(var client=connect(transactionUrl)) {
                var pending=call(client,"create_transaction",Map.of("accountId",args[3],"amount","1","currency","MAD",
                        "merchantCategory","SHOP","country","MA","idempotencyKey","outage-demo"),false,null);
                check("PENDING".equals(pending.get("status")),"Une panne ne doit pas produire COMPLETED");
                System.out.println("PANNE : opération PENDING, aucune réussite bancaire fictive.");
            }
            return;
        }
        var source=createAccount(accountUrl,"1500.000000000000000001");
        var destination=createAccount(accountUrl,"200");
        try(var accounts=connect(accountUrl);var transactions=connect(transactionUrl)) {
            check(accounts.listTools().tools().size()==2,"Exposition inattendue dans account-service");
            check(transactions.listTools().tools().size()==5,"Tools transaction manquants");
            String id=source.get("id").toString(),dest=destination.get("id").toString();
            call(accounts,"get_account",Map.of("id",id),false,null);
            call(accounts,"list_accounts",Map.of("page",0,"size",10),false,null);
            var debit=new HashMap<String,Object>(Map.of("accountId",id,"amount","250.00","currency","MAD",
                    "merchantCategory","SHOP","country","MA","idempotencyKey","demo-debit"));
            var transfer=new HashMap<String,Object>(Map.of("sourceAccountId",id,"destinationAccountId",dest,"amount","250",
                    "currency","MAD","idempotencyKey","demo-transfer"));
            var d=call(transactions,"create_transaction",debit,false,null);
            var t=call(transactions,"create_transfer",transfer,false,null);
            check("COMPLETED".equals(d.get("status")) && "COMPLETED".equals(t.get("status")),"Mouvements non confirmés");
            check(d.get("id").equals(call(transactions,"create_transaction",debit,false,null).get("id")),"Rejeu débit différent");
            check(t.get("id").equals(call(transactions,"create_transfer",transfer,false,null).get("id")),"Rejeu virement différent");
            call(transactions,"get_transaction",Map.of("id",d.get("id")),false,null);
            call(transactions,"get_transfer",Map.of("id",t.get("id")),false,null);
            call(transactions,"list_transactions",Map.of("accountId",id),false,null);
            debit.put("amount","251"); call(transactions,"create_transaction",debit,true,"IDEMPOTENCY_CONFLICT");
            debit.put("amount","-1"); debit.put("idempotencyKey","invalid-amount"); call(transactions,"create_transaction",debit,true,"INVALID_INPUT");
            call(accounts,"get_account",Map.of("id",UUID.randomUUID().toString()),true,"ACCOUNT_NOT_FOUND");
            debit.put("amount","1"); debit.put("accountId",UUID.randomUUID().toString()); debit.put("idempotencyKey","missing-account");
            call(transactions,"create_transaction",debit,true,"ACCOUNT_NOT_FOUND");
            var a=call(accounts,"get_account",Map.of("id",id),false,null);
            var b=call(accounts,"get_account",Map.of("id",dest),false,null);
            check(new BigDecimal(a.get("balance").toString()).compareTo(new BigDecimal("1000.000000000000000001"))==0,"Double débit ou précision perdue");
            check(new BigDecimal(b.get("balance").toString()).compareTo(new BigDecimal("450"))==0,"Double crédit");
            System.out.println("SUCCÈS MCP : initialisation, découverte, lecture, débit, virement, rejeux et erreurs vérifiés.");
            System.out.println("DEMO_SOURCE_ID="+id);
        }
    }
}
