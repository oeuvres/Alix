package alix.lucene;

import java.io.IOException;
import java.util.HashSet;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.FlagsAttribute;

import alix.fr.dic.Tag;
import alix.lucene.TokenDics.LexEntry;
import alix.lucene.TokenDics.NameEntry;
import alix.util.Char;

/**
 * 
 */
public final class TokenLem extends TokenFilter
{
  /** The term provided by the Tokenizer */
  private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  /** A linguistic category as a short number, from Tag */
  private final FlagsAttribute flagsAtt = addAttribute(FlagsAttribute.class);
  /** A lemma when possible */
  private final TokenAttLem tokenAttLem = addAttribute(TokenAttLem.class); // ? needs to be declared in the tokenizer
  /** Last token was Punctuation */
  private boolean waspun;
  /** Store state */
  private State save;
  /** Keep trace of tokens */
  // private final TokenStack stack = new TokenStack();

  /**
   * French, « vois-tu » hyphen is breakable before these words, exc: arc-en-ciel
   */
  public static final HashSet<String> HYPHEN_POST = new HashSet<String>();
  static {
    for (String w : new String[] { "-ce", "-ci", "-elle", "-elles", "-en", "-eux", "-il", "-ils", "-je", "-la", "-là",
        "-le", "-les", "-leur", "-lui", "-me", "-moi", "-nous", "-on", "-t", "-t-", "-te", "-toi", "-tu", "-vous",
        "-y" })
      HYPHEN_POST.add(w);
  }

  /**
   *
   */
  public TokenLem(TokenStream input)
  {
    super(input);
  }

  @Override
  public boolean incrementToken() throws IOException
  {
    if (save != null) {
      restoreState(save);
      save = null;
      return true;
    }
    // end of stream
    if (!input.incrementToken()) return false;
    final boolean waspun = this.waspun;
    TokenAttChar term = (TokenAttChar) termAtt;
    TokenAttChar lem = (TokenAttChar) tokenAttLem;
    int flags = flagsAtt.getFlags();
    // pass through zero-length terms
    if (term.length() == 0) return true;
    if (flags == Tag.PUNdiv || flags == Tag.PUNsent) {
      this.waspun = true;
      // clean the term stack
      return true;
    }
    // normalise œ, É
    TokenDics.norm(term);
    // Get first char
    char c1 = term.charAt(0);
    // a tag do not affect the prev flags
    if (c1 == '<') return true;
    this.waspun = false;
    LexEntry word;
    NameEntry name;
    // norm case
    if (Char.isUpperCase(c1)) {
      name = TokenDics.name(term);
      if (name != null) {
        flagsAtt.setFlags(name.tag);
        return true;
      }
      // if not after pun, say it's a name
      if (!waspun) {
        flagsAtt.setFlags(Tag.NAME);
        return true;
      }
      // test if it is a known word
      term.setCharAt(0, Char.toLower(c1));
      word = TokenDics.word(term);
      if (word == null) {
        // unknown, restore cap, let other filters say better
        term.setCharAt(0, Char.toUpper(c1));
        return true;
      }
    }

    else {
      word = TokenDics.word(term);
      if (word == null) return true;
    }
    // known word
    flagsAtt.setFlags(word.tag);
    if (word.lem != null) {
      lem.append(word.lem);
    }
    return true;
  }

  @Override
  public void reset() throws IOException
  {
    super.reset();
    save = null;
  }
}
