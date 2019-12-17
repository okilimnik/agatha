# Agatha, e-governance, e-voting blockchain based system

## Roadmap
1. Write a webrtc signaling server and client for p2p messaging - done. 
2. Implement STUN/TURN server within the signaling server
3. Make the server to work as client as well.
4. Select a consensus for blockchain.

Known consensuses:

Proof-of-Work (PoW)
Proof-of-Stake (PoS)
Delegated Proof-of-Stake (DPoS)
Byzantine Fault Tolerance (BFT)
Proof of Burn
Proof of Brain
Direct Acyclic Graphs (DAGs) – IOTA’s Tangle is a type of DAG
Proof of Elapsed Time (PoET)

## Development
Made with shadow-cljs.
Please refer to the full [User Guide](https://shadow-cljs.github.io/docs/UsersGuide.html) for more information.

### Run server
In one shell:
```
cd backend && shadow-cljs watch app
```
In another shell after shadow-cljs compiles:
```
node index.js --host=<optional_host> --port=<optional_port> --key=<optional_server_key_path> --cert=<optional_server_certificate_path> --realm=<domain> --username=<optional_turn_username> --password=<optional_turn_password>
```

### Run client
```
cd frontend && shadow-cljs watch app
```
After shadow-cljs compiles open [browser](http://localhost:8020)

## Release
```
cd frontend && shadow-cljs release app && cd .. && shadow-cljs release app
```
## Run as daemon 
I use [pm2](https://pm2.keymetrics.io/docs/usage/quick-start/).
A good [guide](https://www.digitalocean.com/community/tutorials/how-to-set-up-a-node-js-application-for-production-on-ubuntu-18-04)