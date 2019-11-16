package obvil.web;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import alix.util.EnumOption;

public enum Cat implements EnumOption {
  NOSTOP("Mots pleins"), 
  SUB("Substantifs"), 
  NAME("Noms propres"),
  VERB("Verbes"),
  ADJ("Adjectifs"),
  ADV("Adverbes"),
  ALL("Tout"),
  ;
  public final String label;
  private Cat(final String label) {  
    this.label = label ;
  }
  
  public String label()
  {
    return label;
  }
  @Override
  public List<EnumOption> list()
  {
    return list;
  }
  public static List<EnumOption> list;
  static {
    list = Collections.unmodifiableList(Arrays.asList((EnumOption[])values()));
  }
}
