Minimal voting demo using [unicrypt](https://github.com/bfh-evg/univote2) plus code/design from [univote](https://github.com/bfh-evg/univote2). Copied from [Election.scala](https://github.com/agoravoting/sandbox/blob/master/src/main/scala/Election.scala):

    /**
     * An election process DEMO
     *
     * Simulates the steps in the election from public key generation all the way to decryption
     *
     * Things that are included in this demo are:
     *
     * - A typed purely functional data structure modeling the election process and bulletin board (see below)
     *
     * - Cryptography for
     *
     *  a) encrypting votes
     *  b) creating keyshares, proofs and verification
     *  c) shuffling votes, proofs and verification
     *  d) joint (partial) decryption, proofs and verification
     *
     * - Not included
     *
     * Remoting (everything simulated with method calls)
     * Signatures and authentication
     * Error handling
     * Proofs of knowledge of plaintext and verification in vote casting
     *
     *
     * An election is modeled as a typed, purely functional sequential state machine. We use shapeless
     * encoding of natural numbers to provide length-typed lists (aka dependent types), that way we get:
     *
     * 1) The election process logic is captured by types, so illegal transitions
     *    are caught by the compiler and inconsistent states are not possible, for example
     *
     *    It is a compile-time error to try to construct the public key without all the shares
     *    It is a compile-time error to add more shares,shuffles or decryptions than expected
     *    It is a compile-error to start an election with no public key
     *    It is a compile-time error to decrypt without shuffling
     *    etc.
     *
     * 2) Because the election is purely functional, the entire history of the election
     *    can be reconstructed or replayed. A purely functional data structure is in this
     *    sense a general case of an immutable log
     *
     *
     * This demo uses two trustees, ElectionTest3 below shows how number of trustees generalizes
     */
    object ElectionTest extends App {

      // create the keymakers
      // these are responsible for distributed key generation and joint decryption
      val k1 = new KeyMakerTrustee("keymaker one")
      val k2 = new KeyMakerTrustee("keymaker two")

      // create the mixers
      // these are responsible for shuffling the votes
      val m1 = new MixerTrustee("mixer one")
      val m2 = new MixerTrustee("mixer two")

      // create the election,
      // we are using privacy level 2, two trustees of each kind
      // we are 2048 bits for the size of the group modulus
      val start = Election.create[_2]("my election", 2048)

      // the election is now ready to receive key shares
      val readyForShares = Election.startShares(start)

      // each keymaker creates the shares and their proofs, these are added to the election
      val oneShare = Election.addShare(readyForShares, k1.createKeyShare(readyForShares), k1.id)
      val twoShares = Election.addShare(oneShare, k2.createKeyShare(readyForShares), k2.id)

      // combine the shares from the keymaker trustees, this produces the election public key
      val combined = Election.combineShares(twoShares)

      // since we are storing information in election as if it were a bulletin board, all
      // the information is stored in a wire-compatible format, that is strings/jsons whatever
      // we reconstruct the public key as if it had been read from such a format
      val publicKey = Util.getPublicKeyFromString(combined.state.publicKey, combined.state.cSettings.generator)

      // open the election period
      val startVotes = Election.startVotes(combined)

      // generate dummy votes
      val plaintexts = Seq.fill(10)(scala.util.Random.nextInt(10))

      // encrypt the votes with the public key of the election
      val votes = Util.encryptVotes(plaintexts, combined.state.cSettings, publicKey)

      // add the votes to the election
      var electionGettingVotes = startVotes
      votes.foreach { v =>
        electionGettingVotes = Election.addVotes(electionGettingVotes, v.convertToString)
      }

      // stop the voting period
      val stopVotes = Election.stopVotes(electionGettingVotes)

      // prepare for mixing
      val startMix = Election.startMixing(stopVotes)

      // each mixing trustee extracts the needed information from the election
      // and performs the shuffle and proofs
      val shuffle1 = m1.shuffleVotes(startMix)

      // the proof is verified and the shuffle is then added to the election, advancing its state
      val mixOne = Election.addMix(startMix, shuffle1, m1.id)

      // again for the second trustee..
      val shuffle2 = m2.shuffleVotes(mixOne)
      val mixTwo = Election.addMix(mixOne, shuffle2, m2.id)

      // we are done mixing
      val stopMix = Election.stopMixing(mixTwo)

      // start the partial decryptions
      // if we tried to do this before the mixing was completed, the compiler would protest
      val startDecryptions = Election.startDecryptions(stopMix)

      // each keymaker trustee extracts the votes from the last shuffle from the election and
      // uses their private keys to do the partial decryption and create proofs
      val pd1 = k1.partialDecryption(startDecryptions)
      val pd2 = k2.partialDecryption(startDecryptions)

      // the proofs are verified and the partial decryptions are added to the election,
      val partialOne = Election.addDecryption(startDecryptions, pd1, k1.id)
      val partialTwo = Election.addDecryption(partialOne, pd2, k2.id)

      // the partial decryptions are combined, yielding the plaintexts
      val electionDone = Election.combineDecryptions(partialTwo)

      // lets check that everything went well
      println(s"Plaintexts $plaintexts")
      println(s"Decrypted ${electionDone.state.decrypted}")
      println("ok: " + (plaintexts.sorted == electionDone.state.decrypted.map(_.toInt).sorted))
    }

    /**
     * Same as above but with three trustees
     *
     * Note that everything is done the same way except the type parameter _3 and
     * the number of trustee operations
     *
     */
    object ElectionTest3 extends App {

      val k1 = new KeyMakerTrustee("keymaker one")
      val k2 = new KeyMakerTrustee("keymaker two")
      val k3 = new KeyMakerTrustee("keymaker three")

      val m1 = new MixerTrustee("mixer one")
      val m2 = new MixerTrustee("mixer two")
      val m3 = new MixerTrustee("mixer three")

      // privacy level 3, three trustees of each kind, 512 bits for the size of the group modulus
      val start = Election.create[_3]("my election", 512)

      val readyForShares = Election.startShares(start)

      val oneShare = Election.addShare(readyForShares, k1.createKeyShare(readyForShares), k1.id)
      val twoShares = Election.addShare(oneShare, k2.createKeyShare(readyForShares), k2.id)
      val threeShares = Election.addShare(twoShares, k3.createKeyShare(readyForShares), k3.id)

      val combined = Election.combineShares(threeShares)

      val publicKey = Util.getPublicKeyFromString(combined.state.publicKey, combined.state.cSettings.generator)

      val startVotes = Election.startVotes(combined)

      val plaintexts = Seq.fill(100)(scala.util.Random.nextInt(10))

      val votes = Util.encryptVotes(plaintexts, combined.state.cSettings, publicKey)

      var electionGettingVotes = startVotes
      votes.foreach { v =>
        electionGettingVotes = Election.addVotes(electionGettingVotes, v.convertToString)
      }

      val stopVotes = Election.stopVotes(electionGettingVotes)

      val startMix = Election.startMixing(stopVotes)

      val shuffle1 = m1.shuffleVotes(startMix)
      val mixOne = Election.addMix(startMix, shuffle1, m1.id)
      val shuffle2 = m2.shuffleVotes(mixOne)
      val mixTwo = Election.addMix(mixOne, shuffle2, m2.id)
      val shuffle3 = m3.shuffleVotes(mixTwo)
      val mixThree = Election.addMix(mixTwo, shuffle3, m3.id)

      val stopMix = Election.stopMixing(mixThree)

      val startDecryptions = Election.startDecryptions(stopMix)

      val pd1 = k1.partialDecryption(startDecryptions)
      val pd2 = k2.partialDecryption(startDecryptions)
      val pd3 = k3.partialDecryption(startDecryptions)

      val partialOne = Election.addDecryption(startDecryptions, pd1, k1.id)
      val partialTwo = Election.addDecryption(partialOne, pd2, k2.id)
      val partialThree = Election.addDecryption(partialTwo, pd3, k3.id)

      val electionDone = Election.combineDecryptions(partialThree)

      println(s"Plaintexts $plaintexts")
      println(s"Decrypted ${electionDone.state.decrypted}")
      println("ok: " + (plaintexts.sorted == electionDone.state.decrypted.map(_.toInt).sorted))
    }