# Agatha, e-voting system

## Roadmap
1. Webrtc signaling server and client for p2p messaging - [done](https://github.com/mdn/samples-server/tree/master/s/webrtc-from-chat). 
2. STUN/TURN server within the signaling server - [done](https://github.com/Atlantis-Software/node-turn).
3. Consensus.
4. Identity system - [PVID](https://www.researchgate.net/publication/221548697_Pseudo-Voter_Identity_PVID_Scheme_for_e-Voting_Protocols).
5. Privacy-preserving computation problem in e-voting can be resolved simply by taking assumption that voting will take place at a particular time within a short (a few minutes, maybe up to 15 minutes) interval. In that case there is no such problem at all. We don't need a whole day for election and a few days for calculation as it happens on usual elections, everybody now has a cell phone and GSM (or WiFi) network at hand and can vote in few seconds at home or at office. We only need to provide enough bandwidth and network stability.
6. UI.

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
