package alix.grep.query;

import alix.fr.Lexik;
import alix.util.Occ;

/**
 * Zero or more stop words
 * 
 * @author user
 *
 */
public class TestStop extends Test
{

  @Override
  public boolean test(Occ occ)
  {
    // break on comas ?
    // if ( occ.tag().equals( Tag.PUNcl )) return true;
    return Lexik.isStop(occ.orth());
  }

  @Override
  public String label()
  {
    return "##";
  }

}
