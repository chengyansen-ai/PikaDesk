package com.sojourners.chess.manual;

import com.sojourners.chess.model.ManualRecord;
import java.io.Serializable;

public class ChessManual implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private String date;

    private String city;

    private String black;

    private String red;

    private String fenCode;

    private ManualRecord head;

    public String getFenCode() {
        return fenCode;
    }

    public void setFenCode(String fenCode) {
        this.fenCode = fenCode;
    }

    public ManualRecord getHead() {
        return head;
    }

    public void setHead(ManualRecord head) {
        this.head = head;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getBlack() {
        return black;
    }

    public void setBlack(String black) {
        this.black = black;
    }

    public String getRed() {
        return red;
    }

    public void setRed(String red) {
        this.red = red;
    }
}
