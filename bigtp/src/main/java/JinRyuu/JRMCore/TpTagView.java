package JinRyuu.JRMCore;

/**
 * Visao minima de um NBTTagCompound para o nucleo de TP.
 * Existe para permitir testes sem Minecraft e para isolar os nomes SRG.
 */
public interface TpTagView {

    /** Retorno de {@link #tagId(String)} quando a chave nao existe. */
    int ABSENT = -1;

    int TAG_INT = 3;
    int TAG_LONG = 4;

    /** Tipo real da tag (3=INT, 4=LONG) ou {@link #ABSENT}. */
    int tagId(String key);

    int getInt(String key);

    long getLong(String key);

    void setInt(String key, int value);

    void setLong(String key, long value);
}
