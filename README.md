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
cd backend && node index.js
```

### Run client
```
cd frontend && shadow-cljs watch app
```
After shadow-cljs compiles open [browser](http://localhost:8020)

## Heroku deploy
Once run:
```
heroku create
heroku buildpacks:set heroku/nodejs
```
Then every new deploy run:
```
cd frontend && shadow-cljs release app && cd ..
shadow-cljs release app
git add .
git commit -m "changelog"
git push heroku master
```