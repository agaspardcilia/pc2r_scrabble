package game;

import game.Pouch.EmptyPouchException;
import game.exceptions.PropositionException;
import game.exceptions.WordPlacementException;
import game.exceptions.WordPlacementException.Why;
import game.pouches.RandomPouch;
import game.wordchecker.DumbWordChecker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import core.MotherBrain;
import javafx.util.Pair;

public class Scrabble implements Runnable {
	private final static int fiveMinutes = 300;
	private final static int twoMinutes = 120;

	private final static int BOARD_SIZE = 15; //XXX Était à 15 XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX XXX
	private final static int DRAW_SIZE = 7;
	public final static int DEFAULT_POUCH_SIZE = 52; 
	private final static Character NULL_CHAR = '0';
	private final static long RECHERCHE_TIME = fiveMinutes * 1000;
	//	private final static long RECHERCHE_TIME = 3000;
		private final static long SOUMISSION_TIME = twoMinutes * 1000;
//	private final static long SOUMISSION_TIME = 3000;
	private final static long RESULTATS_TIME = 10 * 1000;

	private Lock gameStateLock;

	private Pouch pouch;
	private WordChecker wordChecker;
	private char[][] board;
	private ArrayList<Character> currentDraw;

	private HashMap<String, Integer> scores; //<username, score>
	private Phase currentPhase;

	private long currentPhaseStartingTime;
	private int currentRound;


	//Trucs qui doivent être thread safe.
	//TODO Ajouter une liste de proposition. Une proposition doit avoir un état de jeu, un timestamp et des points(?).
	private ArrayList<Proposition> roundProposition;
	private Proposition firstProposition;
	private PropositionValidator propositionValidator;
	private Thread propositionValidatorThread;

	private Thread gameLoopThread;

	//cache
	private String lazyBoardState;
	private String lazyCurrentDraw;
	private String lazyScores;

	private boolean isGameOn; 

	private MotherBrain motherBrain;

	public Scrabble(Pouch pouch, WordChecker wordChecker, MotherBrain motherBrain) {
		this.pouch = pouch;
		this.wordChecker = wordChecker;
		this.motherBrain = motherBrain;

		board = new char[BOARD_SIZE][BOARD_SIZE];
		currentDraw = new ArrayList<Character>();
		scores = new HashMap<>();
		roundProposition = new ArrayList<>();
		isGameOn = false;
		propositionValidator = new PropositionValidator(this, motherBrain);
		gameStateLock = new ReentrantLock();

	}



	/**CONTROLEURS**/


	public void startGame() {
		System.out.println("Début de la partie.");
		initBoard();
		refreshBoardState();
		switchToNextPhase();
		pouch.resetLetters();
		currentRound = 0;
		propositionValidatorThread = new Thread(propositionValidator);
		propositionValidatorThread.start();
		try {
			drawLetters();
		} catch (EmptyPouchException e) {
			System.out.println("Pouch vide au début de la partie !");
		}
		scores.clear();

		isGameOn = true;
		gameLoopThread = new Thread(this);
		gameLoopThread.start();
	}

	public void stopGame() {
		System.out.println("Arrêt de la partie.");
		isGameOn = false;
		gameLoopThread.interrupt();
	}


	public void handleResultPhase() {
		Proposition winningProposition = null;

		for (Proposition proposition : roundProposition) {
			if (winningProposition == null)
				winningProposition = proposition;
			else if (proposition.getScore() > winningProposition.getScore())
				winningProposition = proposition;

		}

		
		String mot = "";
		for (String w : winningProposition.getFoundWords()) {
			if (w.length() > mot.length())
				mot = w;
		}

		board = winningProposition.getParsedBoard();
		lazyBoardState = winningProposition.getProposition();
		pouch.putLettersBack(winningProposition.getUsedLetters());
		
		
		refreshScore();
		motherBrain.broadcastBilan(mot, winningProposition.getClient().getUsername(), getLazyScores());
	}

	@Override
	public void run() {
		System.out.println("Lancement de la boucle de jeu");
		while (isGameOn) {
			try {
				//Début tour
				System.out.println("Début de tour");
				drawLetters();
				currentRound++;
				roundProposition.clear();
				firstProposition = null;
				refreshBoardState();
				setPhaseToDEB();
				motherBrain.broadcastNewRound(lazyBoardState, lazyCurrentDraw);

				//Phase de Recherche
				switchToNextPhase();
				System.out.println("Phase de recherche");
				try {
					Thread.sleep(RECHERCHE_TIME);
				} catch (InterruptedException e) {
					System.out.println("Interruption Recherche");
					if (!isGameOn)
						break;
				}

				if (firstProposition != null) {
					motherBrain.broadcastRATROUVE(firstProposition.getClient().getUsername());
					pouch.putLettersBack(currentDraw);
					motherBrain.broadcastFinRecherche();
				} else { //Aucune proposition.
					motherBrain.broadcastFinRecherche();
					continue;
				}


				//Phase de soumission
				switchToNextPhase();
				System.out.println("Phase de soumission");
				if (motherBrain.getActiveClients() > 1) { // Un seul joueur donc skip de la phase de soumission.
					try {
						Thread.sleep(SOUMISSION_TIME);
					} catch (InterruptedException e) {
						System.out.println("Interruption Soumission");
						if (!isGameOn)
							break;
					}
				}

				motherBrain.broadcastFinSoumission();

				//Phase de résultat
				switchToNextPhase();
				System.out.println("Phase de résultat");
				handleResultPhase();
				try {
					Thread.sleep(RESULTATS_TIME);
				} catch (InterruptedException e) {
					System.out.println("Interruption Résultats");
					if (!isGameOn)
						break;
				}
				
				switchToNextPhase();

			} catch(EmptyPouchException e) {
				startGame();
				
				motherBrain.broadcastNewSession();
			}
		}
		System.out.println("Fin de la boucle de jeu.");
	}

	public void switchToNextPhase() {
		gameStateLock.lock();
		System.out.println("demande de switch de phase " + currentPhase);
		
		if (currentPhase == null) 
			currentPhase = Phase.DEB;
		else 
			currentPhase = currentPhase.getNext();

		currentPhaseStartingTime = System.currentTimeMillis();
		gameStateLock.unlock();
	}

	public void setPhaseToDEB() {
		gameStateLock.lock();
		currentPhase = Phase.DEB;
		currentPhaseStartingTime = System.currentTimeMillis();
		gameStateLock.unlock();
	}
	
	public void interruptGameLoop() {
		gameLoopThread.interrupt();
	}

	/**FIN CONTROLEURS**/




	/**
	 * Met toutes les cases du plateau de jeu à '0'.
	 */
	private void initBoard() {
		Arrays.stream(board).forEach(b -> Arrays.fill(b, '0'));
	}

	/**
	 * Rafraichit le cache d'état du plateau de jeu.
	 */
	public void refreshBoardState() {
		String newState = "";

		for (char[] cs : board) {
			for (char c : cs) {
				newState += c;
			}
		}

		lazyBoardState = newState;
	}

	public String getBoardState() {
		return lazyBoardState;
	}

	public synchronized void refreshScore() {
		lazyScores = currentRound + "";

		for (String username : scores.keySet()) {
			lazyScores += "*" + username + "*" + scores.get(username);
		}
	}

	public String getLazyScores() {
		return lazyScores;
	}

	/**
	 * Car pouvoir observer l'état du plateau en faisant en sorte que ça ressemble à un plateau c'est cool pour debug.
	 */
	public String getBoardStateForHumans() {
		String result = "";

		for (int i = 0 ; i < BOARD_SIZE; i++) 
			result += lazyBoardState.substring(i, i + BOARD_SIZE) + "\n";

		return result;
	}

	/**
	 * Permet de proposer un réponse.
	 * @param proposedBoard - Réponse sous forme d'état du plateau de jeu avec les nouvelles lettres.
	 * @throws WordPlacementException - La proposition n'est pas bonne. Cela peut vouloir dire que le mot
	 * 		proposé ou les nouveaux mots qu'il engendre n'existe pas. Ou alors ta tentative de triche viens
	 * 		de lamentablement échouer. 
	 */
	public boolean proposeBoard(Proposition proposition) throws WordPlacementException {
		char[][] parsedProposition = toArray(proposition.getProposition());
		proposition.setParsedBoard(parsedProposition);
		
		if (!isPropositionValid(parsedProposition))
			throw new WordPlacementException("Modification des lettres déjà présentes.", Why.INVALID_PROPOSITION);

		ArrayList<ProposedLetter> newLetters = findNewLetters(parsedProposition);

		// Proposition sans nouvelle lettre.
		if (newLetters.isEmpty())
			throw new WordPlacementException("Pas de nouvelle lettre.", Why.INVALID_PROPOSITION);

		boolean isVertical = isPropositionVertical(newLetters);

//		if (!checkHoles(newLetters, proposition, isVertical)) //TODO : tester qu'on n'en a pas besoin
//			throw new WordPlacementException("Il y a un trou dans votre proposition", Why.INVALID_PROPOSITION);

		ArrayList<Pair<String, ProposedLetter[]>> solutions = findNewWords(newLetters, isVertical, parsedProposition);

		if (solutions.isEmpty()) // N'arrive jamais, normalement
			throw new WordPlacementException("Pas de nouveaux mots trouvés", Why.INVALID_PROPOSITION);
		
		ArrayList<String> listWords = new ArrayList<>();
		for (Pair<String, ProposedLetter[]> pair : solutions)
			listWords.add(pair.getKey());
		if (!validateMultipleWords(listWords))
			throw new WordPlacementException("Un ou plusieurs de vos mot n'est pas valide.", Why.INVALID_PROPOSITION); //TODO : garder le mot qui pose problème

		//		if (plateauNeContientPasDeMots) { // TODO : tester (et ajouter une identification qu'il n'y a qu'un mot)
		//			boolean useBoardLetter = false; // Sert à indiquer si aucune mot n'utilise des lettres déjà sur le plateau
		//			for (Pair<String, ProposedLetter[]> pair : solutions) {
		//				useBoardLetter = pair.getKey().length() == pair.getValue().length;
		//				if (useBoardLetter)
		//					break;
		//			}
		//		}
		//		if (! useBoardLetter)
		//			throw new WordPlacementException(Why.INVALID_PROPOSITION);



		proposition.addFoundWord(listWords);
		proposition.setScore(findScore(solutions));
		
		newLetters.forEach(pl -> proposition.addUsedLetter(pl.getLetter()));
		proposition.setValidated(true);
		
		if (currentPhase == Phase.REC && firstProposition == null) 
			firstProposition = proposition;

		roundProposition.add(proposition);
		addPointsToPlayer(proposition.getClient().getUsername(), proposition.getScore());

		return true;
	}


	/**
	 * Vérifie si une proposition ne modifie pas les lettres déja placées sur le plateau de jeu.
	 * @param proposition - Proposition du client à verifier.
	 * @return - La proposition est-elle valide ?
	 * XXX Ça a l'air de fonctionner.
	 */
	public boolean isPropositionValid(char[][] proposition) {
		for (int i = 0; i < board.length; i++) {
			for (int j = 0; j < board[i].length; j++) {
				if (board[i][j] != NULL_CHAR && board[i][j] != proposition[i][j]) 
					return false; // Ancien board altéré. Tricheur...
			}
		}

		return true;
	}

	public void submitProposition(Proposition proposition) throws PropositionException {
		gameStateLock.lock();
		try {
			if (currentPhase == Phase.REC || currentPhase == Phase.SOU) {
				propositionValidator.submit(proposition);
			} else {
				throw new PropositionException("Vous ne pouvez pas soumettre de proposition maintenant." + currentPhase);
			}
		} finally {
			gameStateLock.unlock();
		}
	}

	public boolean checkProposition(Proposition p) {


		return false;
	}

	/**
	 * Trouves les lettres ajoutées et les retournes accompagnées de leur position.
	 * @param proposition - Proposition du client. Là où nous allons chercher de nouvelles lettres.
	 * @return - Liste des nouvelles lettres accompagnées de leurs coordonnées.
	 */
	private ArrayList<ProposedLetter> findNewLetters(char[][] proposition) {
		ArrayList<ProposedLetter> result = new ArrayList<>();

		for (int i = 0; i < BOARD_SIZE; i++) {
			for (int j = 0; j < BOARD_SIZE; j++) {
				if (board[i][j] == NULL_CHAR && proposition[i][j] != NULL_CHAR) {
					result.add(new ProposedLetter(proposition[i][j], i, j));
				}
			}
		}

		return result; 
	}

	/**
	 * Permet de savoir si l'ajout est vartical.
	 * @param newLetters - Lettres ajoutées au plateau.
	 * @return - L'ajout est-il dans le sens vertical ?
	 * @throws WordPlacementException - État des lettre incohérent. Encore une tentative de triche...
	 * XXX Pas testé!
	 */
	private boolean isPropositionVertical(ArrayList<ProposedLetter> newLetters) throws WordPlacementException {
		boolean sameX = true;
		boolean sameY = true;
		int lastX = -1;
		int lastY = -1;


		for (ProposedLetter c : newLetters) {
			if (lastX == -1)
				lastX = c.getX();
			else if (lastX != c.getX())
				sameX = false;

			if (lastY == -1)
				lastY = c.getY();
			else if (lastY != c.getY())
				sameY = false;

		}

		if (!sameX && !sameY)
			throw new WordPlacementException("État des lettres incohérent.", Why.INVALID_PROPOSITION);

		return (sameX) ? false : true;
	}

	public synchronized boolean hasPlayers() {
		return !scores.isEmpty();
	}

	/**
	 * Vérifie si les lettres ajoutées par la proposition sont dans le draw actuel.
	 * @param letters - Lettres ajoutées par la proposition.
	 * @return - L'ajout de ces lettres est-il possible ?
	 */
	private boolean isProposedLettersValid(HashMap<Character, Pair<Integer, Integer>> letters) {
		ArrayList<Character> lazyList = new ArrayList<Character>();
		lazyList.addAll(currentDraw);

		for (Character c : letters.keySet()) {
			if (lazyList.contains(c)) 
				lazyList.remove(c);
			else
				return false;
		}

		return true; 
	}

	/**
	 * Vérifie s'il y a des trous dans les mots qui sont engendrés par les nouvelles lettres.
	 * @param letters - Lettres proposées par le client.
	 * @return - Le placement est-il valide ?
	 * XXX pas testée.
	 */
	private boolean checkHoles(ArrayList<ProposedLetter> letters, Proposition proposition, boolean direction) {
		int min = Integer.MAX_VALUE, max = -1;
		char[][] proposedBoard = proposition.getParsedBoard();
		
		if (direction) { // Vertical

			for (ProposedLetter p : letters) {
				if (p.getY() > max)
					max = p.getY();
				if (p.getY() < min)
					min = p.getY();
			}

			int constX = letters.get(0).getX();


			for (int i = min; i < max; i++) {
				if (proposedBoard[constX][i] == NULL_CHAR)
					return false;
			}

		} else { // Horizontal
			for (ProposedLetter p : letters) {
				if (p.getX() > max)
					max = p.getX();
				if (p.getX() < min)
					min = p.getX();
			}

			int constY = letters.get(0).getY();


			for (int i = min; i < max; i++) {
				if (proposedBoard[i][constY] == NULL_CHAR)
					return false;
			}

		}

		return true;
	}

	/**
	 * Retourne les couples (mots crées sur le plateau ; lettre proposées utilisées)
	 * @param newLetters Lettres proposées
	 * @param direction Lettres placés à la verticale ?
	 * @param proposedBoard Plateau proposé
	 * @return
	 * @throws WordPlacementException - Trou dans le plateau
	 * TODO : vérifier cas d'erreurs
	 */
	public ArrayList<Pair<String, ProposedLetter[]>> 
	findNewWords(ArrayList<ProposedLetter> newLetters, boolean direction, char[][] proposedBoard) throws WordPlacementException {
		ArrayList<Pair<String, ProposedLetter[]>> newWords = new ArrayList<>();
		if (direction) {
			int constY = newLetters.get(0).getY();
			for (ProposedLetter letter : newLetters) { // verification si c'est intéressant de chercher un mot utilisant une seule des lettres proposées
				if ((constY != 0 && board[constY - 1][letter.getX()] != NULL_CHAR) ||
						(constY != BOARD_SIZE - 1 && board[constY + 1][letter.getX()] != NULL_CHAR)) {
					ProposedLetter[] l = { letter };
					newWords.add(new Pair<String, ProposedLetter[]>(findHorizontalWord(l, letter.getX()), l));
				}
			}
			ProposedLetter[] nL = new ProposedLetter[newLetters.size()];
			newLetters.toArray(nL);
			newWords.add(new Pair<String, ProposedLetter[]>(findVerticalWord(nL, constY), nL));
		}
		else {
			int constX = newLetters.get(0).getX();
			for (ProposedLetter letter : newLetters) {
				if ((constX != 0 && board[constX - 1][letter.getY()] != NULL_CHAR) ||
						(constX != BOARD_SIZE - 1 && board[constX + 1][letter.getY()] != NULL_CHAR)) {
					ProposedLetter[] l = { letter };
					newWords.add(new Pair<String, ProposedLetter[]>(findHorizontalWord(l, letter.getY()), l));
				}
			}
			ProposedLetter[] nL = new ProposedLetter[newLetters.size()];
			newLetters.toArray(nL);
			newWords.add(new Pair<String, ProposedLetter[]>(findVerticalWord(nL, constX), nL));
		}
		return newWords;
	}

	private String findVerticalWord(ProposedLetter[] l, int x) throws WordPlacementException {
		String newWord = "";
		boolean isNewWord = false;
		ProposedLetter curLetter;
		int counter = l.length; // pour verifier si il y a des trous (= toutes les lettres sont utilises)
		for (int y = 0 ; y < BOARD_SIZE ; y++) {
			curLetter = findLetter(l, x, y);
			if (board[x][y] == NULL_CHAR && curLetter == null) {
				if (isNewWord)
					return newWord;
				newWord = "";
			}
			else if (board[x][y] != NULL_CHAR)
				newWord += board[x][y];
			else {
				newWord += curLetter.getLetter();
				isNewWord = true;
				counter--;
			}
		}
		if (counter != 0)
			throw new WordPlacementException("Pas de mot trouvé (V).", Why.INVALID_PROPOSITION);
		return newWord;
	}

	private String findHorizontalWord(ProposedLetter[] l, int y) throws WordPlacementException {
		String newWord = "";
		boolean isNewWord = false;
		ProposedLetter curLetter;
		int counter = l.length;
		for (int x = 0 ; x < BOARD_SIZE ; x++) {
			curLetter = findLetter(l, x, y);
			if (board[x][y] == NULL_CHAR && curLetter == null) {
				if (isNewWord)
					return newWord;
				newWord = "";
			}
			else if (board[x][y] != NULL_CHAR)
				newWord += board[x][y];
			else {
				newWord += curLetter.getLetter();
				isNewWord = true;
				counter--;
			}
		}
		if (counter != 0)
			throw new WordPlacementException("Pas de mot trouvé (H).", Why.INVALID_PROPOSITION);
		return newWord;
	}

	private ProposedLetter findLetter(ProposedLetter[] l, int x, int y) {
		for (ProposedLetter proposedLetter : l) {
			if (proposedLetter.getX() == x && proposedLetter.getY() == y)
				return proposedLetter;
		}
		return null;
	}

	private boolean validateMultipleWords(ArrayList<String> words) {
		return words.stream().allMatch(w -> wordChecker.isWordValid(w));
	}

	/**
	 * Renvoi le score marque grace aux mots / lettre posees
	 * @param solutions
	 * @return
	 */
	private int findScore(ArrayList<Pair<String, ProposedLetter[]>> solutions) { //TODO : TESTER
		int score, mult, finalScore = 0;
		for (Pair<String, ProposedLetter[]> pair : solutions) {
			score = 0;
			mult = 1;
			for (char c : pair.getKey().toCharArray())
				score += ScoreByLetterFr.letterPoints(c);
			for (ProposedLetter pl : pair.getValue()) {
				switch(ScoreByCase.findScoreCase(pl.getX(), pl.getY())) {
				case DL:
					score += ScoreByLetterFr.letterPoints(pl.getLetter());
					break;
				case TL:
					score += ScoreByLetterFr.letterPoints(pl.getLetter()) * 2;
					break;
				case DW:
					mult *= 2;
					break;
				case TW:
					mult *= 3;
					break;
				default:
					break;
				}
			}
			finalScore += score * mult;
		}
		return finalScore;
	}

	//	private ArrayList<Character> letterAlreadyUsed(String word, ProposedLetter[] letterPlayer) {
	//		ArrayList<Character> letters = new ArrayList<Character>();
	//		char[] cS = word.toCharArray();
	//		ProposedLetter[] pl = letterPlayer.clone();
	//		for (int i = 0; i < cS.length; i++) {
	//			char c = cS[i];
	//			int j = 0;
	//			for (; j < pl.length; j++) {
	//				if (pl[j].getLetter().equals(c)) {
	//					pl[j] = null;
	//					break;
	//				}
	//			}
	//			if (j == pl.length)
	//				letters.add(c);
	//		}
	//		return letters;
	//	}



	public void drawLetters() throws EmptyPouchException {
		currentDraw.clear();
		currentDraw.addAll(pouch.pickLetters(DRAW_SIZE));

		lazyCurrentDraw = "";
		for (Character c : currentDraw) 
			lazyCurrentDraw += c;

	}

	public String getLazyCurrentDraw() {
		return lazyCurrentDraw;
	}


	public Phase getCurrentPhase() {
		return currentPhase;
	}

	// XXX pour debug, rien d'autre!
	public void setBoard(char[][] board) {
		this.board = board;
	}

	public synchronized void addPlayer(String username) {
		scores.put(username, 0);
		refreshScore();
	}

	public synchronized void removePlayer(String username) {
		scores.remove(username);
		roundProposition.removeIf(p -> {
			return p.getClient().getUsername().equals(username);
		});
	}

	public synchronized void addPointsToPlayer(String username, int points) {
		scores.put(username, scores.get(username) + points);
		refreshScore();
	}

	public synchronized boolean isUserNameAvailable(String username) {
		return !scores.containsKey(username);
	}

	public Lock getGameStateLock() {
		return gameStateLock;
	}

	public int getTotalCurrentPhaseTime() {
		switch (currentPhase) {
		case DEB:
			return 0;
		case REC:
			return fiveMinutes;
		case SOU:
			return twoMinutes;
		case RES:
			return 10;
		default :
			return -1;
		}
	}

	public int getPhaseRemainingTimeInSecondes() {
		return getTotalCurrentPhaseTime() - (int) ((System.currentTimeMillis() - currentPhaseStartingTime) / 1000);
	}

	public String getPlacement() {
		String result = "";

		for (char[] cs : board) {
			for (char c : cs) {
				result += c;
			}
		}

		return result;
	}

	
	public int getPropositionCount() {
		return roundProposition.size();
	}
	
	/**
	 * Retourne l'état complet de la partie actuelle. Utile pour BIENVENUE.
	 */
	public synchronized String[] getCompleteGameState() {
		String placement = lazyBoardState;
		String tirage = lazyCurrentDraw;
		String score = lazyScores;
		String phase = currentPhase.toString();
		String temps = getPhaseRemainingTimeInSecondes() + "";

		return new String[] {placement, tirage, score, phase, temps};
	}

	/**
	 * Convertie une String en tableau de char.
	 * @param rawProposition - Chaine à convertir.
	 * @return - Conversion de la chaine.
	 * @throws WordPlacementException - Tentative de triche ou erreur du client.
	 */
	public static char[][] toArray(String rawProposition) throws WordPlacementException {
		if (rawProposition.length() != BOARD_SIZE * BOARD_SIZE) 
			throw new WordPlacementException("La proposition n'est pas à la bonne taille (" + rawProposition.length() + " -> " +  BOARD_SIZE * BOARD_SIZE + ").", Why.INVALID_PROPOSITION);

		char[][] result = new char[BOARD_SIZE][BOARD_SIZE];

		int x, y;
		for (int i = 0; i < rawProposition.length(); i++) {

			if (isValidChar(rawProposition.charAt(i))) {
				x = i / BOARD_SIZE;
				y = i % BOARD_SIZE;

				result[x][y] = rawProposition.charAt(i);
			} else {
				throw new WordPlacementException("Charactère invalide trouvé.", Why.INVALID_PROPOSITION);
			}

		}


		return result;
	}

	public static boolean isValidChar(char c) {
		return c == NULL_CHAR || (c >= 'A' && c <= 'Z');
	}
	

	public static void setTimeout(Runnable runnable, int delay){
		new Thread(() -> {
			try {
				Thread.sleep(delay);
				runnable.run();
			}
			catch (Exception e){
				System.err.println(e);
			}
		}).start();
	}


	public enum Phase {
		DEB, //Début
		REC, //Recherche
		SOU, //Soumission
		RES; //Résultat


		public Phase getNext() {
			switch (this) {
			case DEB:
				return REC;
			case REC:
				return SOU;
			case SOU:
				return RES;
			case RES:
				return DEB;
			}

			return DEB;
		}

		public String toString() {
			switch (this) {
			case DEB:
				return "DEB";
			case REC:
				return "REC";
			case SOU:
				return "SOU";
			case RES:
				return "RES";

			}

			return ""; //Never reached.
		}
	}

	public static void main(String[] args) {
		Scrabble s = new Scrabble(new RandomPouch(42), new DumbWordChecker(), null);

//		char[][] b1 = new char[][]{{'0', '0', '0'}, {'A', 'B', 'C'}, {'0', '0', '0'}};
//		char[][] b2 = new char[][]{{'0', '0', '0'}, {'0', '0', '0'}, {'0', '0', '0'}};
//
//		s.setBoard(b2);
//
//		System.out.println(s.isPropositionValid(b1));
//		ArrayList<ProposedLetter> letters = new ArrayList<ProposedLetter>();
//
//		letters.add(new ProposedLetter('A', 1, 3));
//		letters.add(new ProposedLetter('B', 5, 3));
//		letters.add(new ProposedLetter('C', 3, 30));
//		letters.add(new ProposedLetter('D', 1, 3));
//
//		ArrayList<ProposedLetter> validLetters = s.findNewLetters(b1);
//
//		for (ProposedLetter proposedLetter : validLetters)
//			System.out.println(proposedLetter.getX() + ";" + proposedLetter.getY() + ":" + proposedLetter.getLetter());
//
//		try {
//			ArrayList<Pair<String, ProposedLetter[]>> solutions = s.findNewWords(validLetters, s.isPropositionVertical(validLetters), b1);
//			System.out.println("OK");
//			System.out.println(solutions.size());
//			for (Pair<String, ProposedLetter[]> pair : solutions) {
//				System.out.print(pair.getKey());
//				for (ProposedLetter pL : pair.getValue())
//					System.out.println("(" + pL.getLetter() + " : " + pL.getX() + ";" + pL.getY() + ")");
//			}
//		} catch (WordPlacementException e) {
//			e.printStackTrace();
//		}
		
		char[][] testBoard=new char[][]{{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', 'T', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', 'A', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', 'T', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', 'A', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
										{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'}};
		
		char[][] testBoardProposition=new char[][]{	{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', 'T', 'A', 'B', 'L', 'E', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', 'A', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', 'T', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', 'A', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'},
													{'0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0', '0'}};
		Proposition testPropositionSucc = new Proposition(null, "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000TABLE0000000000A00000000000000T00000000000000A0000000000000000000000000000000000000000000000000000000000000000000000000000000000", 40);
		// Trou dans le mot
		Proposition testPropositionFail1 = new Proposition(null, "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000TABL0E000000000A00000000000000T00000000000000A0000000000000000000000000000000000000000000000000000000000000000000000000000000000", 40);
		// Pas raccord
		Proposition testPropositionFail2 = new Proposition(null, "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000T0BLE0000000000A00000000000000T00000000000000A0000000000000000000000000000000000000000000000000000000000000000000000000000000000", 40);
		s.setBoard(testBoard);
		try {
			s.proposeBoard(testPropositionFail2);
			if (s.roundProposition.get(s.roundProposition.size() - 1).equals(testPropositionFail2))
				System.out.println("success");
			else
				System.out.println("failure");
		} catch (WordPlacementException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}



	

}
